/*
 * skipd - 常驻守护：盯住引擎的两条命——进程在不在、服务绑没绑，坏了都用同一套动作修。
 *
 * 两种坏法：① 进程没了（pidfd 事件）；② 进程还在但服务被 AMS 判成 crashed，
 * 这种不会自愈，只能把本服务从 enabled_accessibility_services 摘掉再挂回去
 * （写回同一份名单等于没写，accessibility_enabled 0/1 更是 no-op）。
 * 事件口径：进程退出是内核事件，不靠轮询发现；服务状态每 2s 复检一次 dumpsys。
 * 唯一的周期动作是每 5s 重刷一次 oom_score_adj——AMS 会按它对进程的判定不断改写这个值，
 * 只写一次的守护等于没写。
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#ifndef __NR_pidfd_open
#define __NR_pidfd_open 434
#endif

#define VERSION_MARK "skipd-ok"
#define OOM_SELF (-1000)
#define ASSERT_INTERVAL_MS 5000
#define RESTART_MIN_MS 2000
#define WAIT_PID_TIMEOUT_MS 60000
/* 重绑名单后进程出现得比 am 快，先只等这一小段，等不到才走可见兜底 */
#define REBIND_WAIT_MS 8000
/* 修故障态时的复检节奏：service_crashed() 要跑一次 dumpsys，别按 500ms 轮 */
#define CRASH_CHECK_INTERVAL_MS 2000
/*
 * 摘名单与挂名单之间的间隔。A16/A17 的变更观察者是异步处理的
 * （readEnabledAccessibilityServicesLocked 只在集合内容真的不等时才判成"变了"），
 * 连着写可能被合并成"内容没变"，crashed 集合就永远清不掉。
 */
#define REBIND_LIST_GAP_S 2
/*
 * 两次拉服务的最小间隔：他钉的口径是「守护不能高频拉服务，两次间隔至少十分钟」。
 * 重绑动的是 enabled_accessibility_services 名单（本机名单里三家共享同一条 secure 设置），
 * 闸门按单调时钟算，不靠 sleep 的精度。
 */
#define RELAUNCH_MIN_INTERVAL_MS (10 * 60 * 1000)

static char g_pkg[256] = "";
static char g_component[512] = "";
static char g_launch[512] = "";
static char g_status[512] = "";
static int g_oom = -900;
static volatile sig_atomic_t g_quit = 0;

static void on_term(int sig)
{
    (void)sig;
    g_quit = 1;
}

static int64_t now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void nap(int ms)
{
    struct timespec ts;
    int left = ms;
    while (left > 0 && !g_quit) {
        ts.tv_sec = left / 1000;
        ts.tv_nsec = (long)(left % 1000) * 1000000L;
        nanosleep(&ts, NULL);
        left -= ms;
    }
}

/*
 * 睡到某个单调时钟时刻：分片睡，收到停止信号要立刻醒——
 * 一次十分钟的 nanosleep 会让 pkill 之后守护还占着进程不放。
 */
static void nap_until(int64_t deadline_ms)
{
    while (!g_quit) {
        int64_t left = deadline_ms - now_ms();
        if (left <= 0)
            return;
        nap(left > 1000 ? 1000 : (int)left);
    }
}

static void trace(const char *fmt, ...)
{
    char body[256];
    va_list ap;

    va_start(ap, fmt);
    vsnprintf(body, sizeof body, fmt, ap);
    va_end(ap);
    printf("%lld %s\n", (long long)now_ms(), body);
    fflush(stdout);
}

/* 主进程 cmdline 严格等于包名；:channel 之类的子进程不算。 */
static int cmdline_is(int pid, const char *want)
{
    char path[64];
    char buf[512];
    ssize_t n;
    int fd;

    snprintf(path, sizeof path, "/proc/%d/cmdline", pid);
    fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0)
        return 0;
    n = read(fd, buf, sizeof buf - 1);
    close(fd);
    if (n <= 0)
        return 0;
    buf[n] = '\0';
    return strcmp(buf, want) == 0;
}

static int find_pid(void)
{
    DIR *dir;
    struct dirent *ent;

    dir = opendir("/proc");
    if (!dir)
        return 0;
    while ((ent = readdir(dir)) != NULL) {
        int pid = atoi(ent->d_name);
        if (pid > 0 && cmdline_is(pid, g_pkg)) {
            closedir(dir);
            return pid;
        }
    }
    closedir(dir);
    return 0;
}

static int pid_alive(int pid)
{
    char path[64];

    if (pid <= 0)
        return 0;
    snprintf(path, sizeof path, "/proc/%d", pid);
    return access(path, F_OK) == 0;
}

static void set_oom(int pid, int value)
{
    char path[64];
    char text[16];
    ssize_t rc;
    int fd;

    snprintf(path, sizeof path, "/proc/%d/oom_score_adj", pid);
    snprintf(text, sizeof text, "%d", value);
    fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0)
        return;
    rc = write(fd, text, strlen(text));
    if (rc < 0)
        trace("set oom %d=%d failed: %s", pid, value, strerror(errno));
    close(fd);
}

/*
 * 从应用所属的 uid cgroup 里搬出去。Android 12+ 的 force-stop / 划掉后台是往
 * /sys/fs/cgroup/apps/uid_N/cgroup.kill 写 1，整棵子树一起杀；守护是应用的 su 子进程生出来的，
 * 不搬就等于陪着引擎一起死。v1 的 cpu/cpuset 也一并挪——哪套在用随机型，写失败只记不致命。
 */
static void escape_cgroup(void)
{
    static const char *const targets[] = {
        "/sys/fs/cgroup/cgroup.procs",
        "/dev/cpuctl/tasks",
        "/dev/cpuset/tasks",
    };
    char text[16];
    char seen[256];
    ssize_t rc;
    size_t len;
    size_t i;
    int fd;

    snprintf(text, sizeof text, "%d", (int)getpid());
    for (i = 0; i < sizeof targets / sizeof targets[0]; i++) {
        fd = open(targets[i], O_WRONLY | O_CLOEXEC);
        if (fd < 0) {
            trace("escape %s: %s", targets[i], strerror(errno));
            continue;
        }
        rc = write(fd, text, strlen(text));
        if (rc < 0)
            trace("escape %s: %s", targets[i], strerror(errno));
        close(fd);
    }
    /* 读回内核记的那份，别只信自己写过 */
    seen[0] = '\0';
    fd = open("/proc/self/cgroup", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        rc = read(fd, seen, sizeof seen - 1);
        close(fd);
        if (rc > 0)
            seen[rc] = '\0';
        else
            seen[0] = '\0';
    }
    len = strlen(seen);
    while (len > 0 && (seen[len - 1] == '\n' || seen[len - 1] == '\r'))
        seen[--len] = '\0';
    trace("cgroup now %s", seen[0] != '\0' ? seen : "(读不到)");
}

static void write_status(const char *state, int engine_pid, int restarts)
{
    char path[600];
    FILE *fp;

    if (g_status[0] == '\0')
        return;
    snprintf(path, sizeof path, "%s.tmp", g_status);
    fp = fopen(path, "w");
    if (!fp)
        return;
    fprintf(fp, "%s daemon=%d engine=%d restarts=%d\n", state, (int)getpid(), engine_pid, restarts);
    fclose(fp);
    rename(path, g_status);
}

/*
 * 跑一条 shell 命令并等它退出，返回 0 = 退出码 0。
 * am / settings 都是脚本、要靠 PATH，所以统一走 sh -c；
 * 输出不吞：守护的 stderr 已经接到 .status.log，失败原因（Error type 3 /
 * Permission Denial）必须留在日志里，第 10 轮就是栽在静默失败上。
 */
static int run_sh(const char *cmd, const char *label)
{
    pid_t pid = fork();
    int status;

    if (pid < 0) {
        trace("%s fork failed: %s", label, strerror(errno));
        return -1;
    }
    if (pid == 0) {
        signal(SIGTERM, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        signal(SIGHUP, SIG_DFL);
        execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
        _exit(127);
    }
    if (waitpid(pid, &status, 0) < 0) {
        trace("%s waitpid failed: %s", label, strerror(errno));
        return -1;
    }
    if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        trace("%s 退出码非 0（raw=0x%x）：%s", label, status, cmd);
        return 1;
    }
    return 0;
}

/*
 * 只要退出码、不写日志的 sh：service_crashed() 是判定用的，grep 没命中（退出码 1）
 * 是正常结果，套 run_sh 会把状态日志刷满。fork/exec/wait 本身出问题才出声。
 */
static int sh_code(const char *cmd, int *code)
{
    pid_t pid = fork();
    int status;

    *code = -1;
    if (pid < 0) {
        trace("sh_code fork failed: %s", strerror(errno));
        return -1;
    }
    if (pid == 0) {
        signal(SIGTERM, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        signal(SIGHUP, SIG_DFL);
        execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
        _exit(127);
    }
    if (waitpid(pid, &status, 0) < 0) {
        trace("sh_code waitpid failed: %s", strerror(errno));
        return -1;
    }
    if (!WIFEXITED(status))
        return -1;
    *code = WEXITSTATUS(status);
    return 0;
}

/*
 * 我们的服务有没有被 AccessibilityManagerService 记进 crashed 集合。
 * A16/A17 的 updateServicesLocked() 对该集合里的组件直接 continue ⇒ 进去就不会自己出来，
 * 设置页从此显示故障。这里唯一能读到的判据是 dumpsys 的 "Crashed services:" 那一行
 * （dumpsys 要 shell 或 root，守护两档都满足）；同行里只出现崩溃组件的组件名，
 * 所以按整串字面匹配（grep -F）。读不到就按「没坏」处理：宁可漏修一次（闸门后还会再看），
 * 也不要在判据不明时去抖全局名单。
 */
static int service_crashed(void)
{
    char cmd[768];
    int code = -1;

    if (g_component[0] == '\0')
        return 0;
    snprintf(cmd, sizeof cmd,
             "dumpsys accessibility | grep 'Crashed services' | grep -q -F '%s'", g_component);
    if (sh_code(cmd, &code) != 0)
        return 0;
    return code == 0;
}

/*
 * 自己把无障碍开关关掉再打开：把本组件从 enabled_accessibility_services 摘掉，隔两秒再原样挂回去。
 *
 * 为什么不能翻 accessibility_enabled 主开关（第 12 轮那版就是这么写的）：那个值是 AMS 按
 * 「有没有 bound service」自己回写的**输出**，既不被监听也不参与 bind 判定；而清 crashed 集合的
 * removeDisabledServicesFromTemporaryStatesLocked() 只在名单 URI 变化、且集合内容真的不等时才执行
 * ——所以「写回一模一样的名单」也是空操作，必须两笔写之间让系统真的看见一次内容变化。
 *
 * 只摘本包名开头的项（两种写法都算），别家一项都不动；第二笔写回的是第一笔读到的原样，
 * 不重建。中间那两秒里服务在名单外，用户此刻去设置页会看到开关是关的；守护正好在这中间被杀的话
 * 服务就留在关闭态，等应用侧的 ensureAccessibility 或下一次开机广播补回。
 */
static int rebind_via_list(void)
{
    char cmd[1984];

    if (g_component[0] == '\0') {
        trace("rebind skipped: 没传 --component，改名单要精确的组件名");
        return -1;
    }
    snprintf(cmd, sizeof cmd,
             "pkg='%s'; cmp='%s';"
             " cur=$(settings get secure enabled_accessibility_services);"
             " case \"$cur\" in ''|null) echo '名单读不到'; exit 1;; esac;"
             " red='';"
             " for it in $(echo \"$cur\" | tr ':' ' '); do"
             " case \"$it\" in \"$pkg/\"*) ;; *) red=\"${red:+$red:}$it\";; esac;"
             " done;"
             " settings put secure enabled_accessibility_services \"$red\";"
             " sleep %d;"
             " settings put secure enabled_accessibility_services \"$cur\";"
             " test \"$(settings get secure enabled_accessibility_services)\" = \"$cur\""
             " && echo list-toggled;",
             g_pkg, g_component, REBIND_LIST_GAP_S);
    return run_sh(cmd, "rebind via list");
}

/*
 * 可见兜底：am start 起 Activity。--launch 必须是 **Activity**——
 * `am start -n` 喂服务组件只会回 Error type 3，第 10 轮那条「relaunch produced no pid」
 * 就是把无障碍服务名传了进来。
 */
static void launch_app(void)
{
    char cmd[640];

    if (g_launch[0] == '\0')
        return;
    snprintf(cmd, sizeof cmd, "am start -n %s", g_launch);
    run_sh(cmd, "am start");
}

/*
 * 等进程退出。返回 1 = 进程确实没了，0 = 被信号打断继续退出。
 * 每次超时（5s）顺带重刷 oom_score_adj，这是唯一周期动作。
 */
static int wait_death(int pid)
{
    struct pollfd pfd;
    int fd = (int)syscall(__NR_pidfd_open, pid, 0);

    if (fd >= 0) {
        pfd.fd = fd;
        pfd.events = POLLIN;
        while (!g_quit) {
            int rc = poll(&pfd, 1, ASSERT_INTERVAL_MS);
            if (rc < 0) {
                if (errno == EINTR)
                    continue;
                break;
            }
            if (rc > 0 && (pfd.revents & POLLIN) != 0) {
                close(fd);
                return 1;
            }
            set_oom(pid, g_oom);
        }
        close(fd);
        return 0;
    }
    /* 内核不支持 pidfd：退成 200ms 存在性检查，仍带 5s 的 oom 重刷 */
    trace("pidfd_open(%d) failed: %s, fall back to 200ms check", pid, strerror(errno));
    {
        int64_t last_assert = now_ms();
        while (!g_quit) {
            if (!pid_alive(pid))
                return 1;
            nap(200);
            if (now_ms() - last_assert >= ASSERT_INTERVAL_MS) {
                set_oom(pid, g_oom);
                last_assert = now_ms();
            }
        }
        return 0;
    }
}

/*
 * 等「引擎进程在」而且（本轮开始时有故障态的话）「服务不再是故障态」两件事同时成立。
 * must_uncrash 不能省：进程可能是用户从别处拉起来的，那时服务照旧没绑，
 * 只按 pid 判就会把没修好的一轮记成修好了。
 */
static int wait_ok(int64_t timeout_ms, int must_uncrash)
{
    int64_t deadline = now_ms() + timeout_ms;
    int64_t next_check = 0;

    while (!g_quit && now_ms() < deadline) {
        int pid = find_pid();
        if (pid > 0) {
            if (!must_uncrash)
                return pid;
            if (now_ms() >= next_check) {
                next_check = now_ms() + CRASH_CHECK_INTERVAL_MS;
                if (!service_crashed())
                    return pid;
            }
        }
        nap(500);
    }
    return 0;
}

static int parse_args(int argc, char **argv)
{
    int i;

    for (i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-v") == 0 || strcmp(argv[i], "--version") == 0) {
            puts(VERSION_MARK);
            exit(0);
        }
        if (strcmp(argv[i], "-h") == 0 || strcmp(argv[i], "--help") == 0)
            continue;
        if (i + 1 >= argc)
            return -1;
        if (strcmp(argv[i], "--pkg") == 0)
            snprintf(g_pkg, sizeof g_pkg, "%s", argv[++i]);
        else if (strcmp(argv[i], "--component") == 0)
            snprintf(g_component, sizeof g_component, "%s", argv[++i]);
        else if (strcmp(argv[i], "--launch") == 0)
            snprintf(g_launch, sizeof g_launch, "%s", argv[++i]);
        else if (strcmp(argv[i], "--status") == 0)
            snprintf(g_status, sizeof g_status, "%s", argv[++i]);
        else if (strcmp(argv[i], "--oom") == 0) {
            int v = atoi(argv[++i]);
            if (v >= -1000 && v <= 1000)
                g_oom = v;
        } else
            return -1;
    }
    if (g_pkg[0] == '\0')
        return -1;
    return 0;
}

int main(int argc, char **argv)
{
    int engine = 0;
    int restarts = 0;
    int rebind_rc = 0;
    int64_t last_relaunch = 0;
    int64_t started;

    if (parse_args(argc, argv) != 0) {
        fprintf(stderr, "usage: skipd --pkg PKG [--component PKG/SERVICE] [--launch PKG/ACTIVITY]"
                        " [--status FILE] [--oom N]\n"
                        "  拉活靠 --component：把该组件从无障碍名单里摘掉再挂回去，系统才会重新 bind；\n"
                        "  --launch 只是可见兜底，起界面不清故障态\n");
        return 2;
    }
    signal(SIGTERM, on_term);
    signal(SIGINT, on_term);
    /* 终端断了就收 SIGHUP：自己先挡掉，保活不依赖外面用不用 nohup 拉 */
    signal(SIGHUP, SIG_IGN);
    signal(SIGPIPE, SIG_IGN);
    signal(SIGCHLD, SIG_DFL);

    /* 守护自身不可杀；失败只是权限不够，不影响盯进程 */
    set_oom((int)getpid(), OOM_SELF);
    /* 搬出应用的 uid cgroup：否则 force-stop 写 cgroup.kill 时守护跟引擎一起被杀 */
    escape_cgroup();
    trace("start pkg=%s component=%s launch=%s oom=%d", g_pkg, g_component, g_launch, g_oom);

    while (!g_quit) {
        int crashed;

        if (engine > 0 && !pid_alive(engine)) {
            trace("engine pid=%d gone", engine);
            write_status("lost", 0, restarts);
            engine = 0;
            nap(RESTART_MIN_MS);
        }
        if (engine <= 0)
            engine = find_pid();
        /* 进程还在但服务被系统记成故障，一样要修——开机被 ROM 杀掉之后就是这个形状 */
        crashed = service_crashed();
        if (engine > 0 && !crashed) {
            set_oom(engine, g_oom);
            write_status("alive", engine, restarts);
            trace("watching engine pid=%d", engine);
            wait_death(engine);
            continue;
        }
        /*
         * 没进程，或者有进程但服务没绑上：都算要拉一次服务。
         * 两次拉最少隔 10 分钟（他钉的口径：守护不能高频拉服务）。重绑动的是三家共享的
         * 无障碍名单，拉不起来就一直抖，比等十分钟更坏。
         */
        if (last_relaunch != 0) {
            int64_t next = last_relaunch + RELAUNCH_MIN_INTERVAL_MS;
            if (now_ms() < next) {
                trace("relaunch gated for %lldms", (long long)(next - now_ms()));
                nap_until(next);
                if (g_quit)
                    break;
                /* 等的这段时间里，系统或用户可能已经把它修好了 */
                engine = find_pid();
                {
                    int still = service_crashed();
                    if (engine > 0 && !still)
                        continue;
                    /* 闸门期间才出现的故障态，本轮的判据要跟着改严 */
                    if (still)
                        crashed = 1;
                }
            }
        }
        last_relaunch = now_ms();
        started = now_ms();
        /* 先自己把开关关掉再打开（无窗口，shell uid 也做得动），等不到才走可见的 am start */
        rebind_rc = rebind_via_list();
        engine = wait_ok(REBIND_WAIT_MS, crashed);
        if (engine <= 0 && g_launch[0] != '\0') {
            trace("rebind got no healthy engine in %lldms, fall back to am start",
                  (long long)(now_ms() - started));
            launch_app();
            engine = wait_ok(WAIT_PID_TIMEOUT_MS, crashed);
        }
        if (engine > 0) {
            restarts++;
            trace("engine back as pid=%d in %lldms", engine, (long long)(now_ms() - started));
        } else if (rebind_rc != 0 && g_launch[0] == '\0') {
            /* 两条路都不在（改名单跑不动、又没有可见兜底可退）才收工 */
            trace("no way to relaunch: rebind rc=%d, launch empty", rebind_rc);
            break;
        } else {
            /* 这一轮没修好不等于该收工：下一轮前面那道 10 分钟闸门会等 */
            trace("relaunch produced no healthy engine in %lldms",
                  (long long)(now_ms() - started));
        }
    }
    write_status("exit", engine, restarts);
    trace("daemon exit, restarts=%d", restarts);
    return 0;
}
