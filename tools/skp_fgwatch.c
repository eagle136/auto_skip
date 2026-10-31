/*
 * skp_fgwatch —— 前台应用监听（root 专用）
 *
 * 为什么不用无障碍的 TYPE_WINDOW_STATE_CHANGED：真机实测它晚 2.4–3.1s 才投递，
 * 而且不同应用差几秒（冷启动 vs 热启动、Activity 是否复用、ROM 版本都会影响）。
 * 系统自己在前台 Activity 变化的那一刻就会往 events buffer 写一条
 *   wm_set_resumed_activity: [0,<包名>/<Activity>,<reason>]
 * 这条是 WindowManager 打点，几乎与切换同刻。这里直接盯它，拿到包名就原子写文件，
 * APK 侧用 FileObserver 监听文件变化，立刻开一轮识别。
 *
 * ★ logcat 的坑（全踩过，别再改回去）：
 *   1. `-T 1` 不是「从现在开始」，而是「从时间戳 1 开始」= 整个 buffer 全吐。
 *      服务一连上就是几十条历史包名连环触发（20:30:44.6 那一秒刷了 50+ 条）。
 *   2. `-T now` 在本机 logcat 上**不被接受**：`-T 'now' not in time format`，
 *      logcat 直接退出，跟随循环变成每秒重启一次空转。
 *   3. 时间戳只能用 `MM-DD HH:MM:SS.mmm` 且必须是**设备本地时间**。
 *      这个 ELF 是 glibc 静态链接的，没有 bionic 那套时区逻辑，localtime 会退化成
 *      UTC（比 CST 差 8 小时 = 时间戳在未来，一条都等不到）。所以时间一律问
 *      shell 的 `date`，不自己算。
 *   4. ★★ `popen()` 读 logcat 会卡住几秒（v4 的致命伤）。logcat 的 stdout 一旦是
 *      **管道**就是全缓冲，一行 120 字节远不够 4KB，于是要等攒满或进程退出才吐出来。
 *      表现就是「有时 160ms、有时 3s」的随机抖动，实测 23:04:41.143 的切换
 *      到 23:04:44.118 才收到 = 2975ms，广告早播完了。
 *      v5 改成 **pty（伪终端）**：logcat 认为自己在写终端 → 行缓冲 → 每行立即出来。
 *      千万别再改回 popen/pipe。
 *
 * ★ 起始「当前前台」不能读 events buffer 的最后一条：**桌面根本不发这条点**，
 *   force-stop 回桌面后 buffer 里最后一条还是上一个应用，会读出一个错的「当前前台」
 *   （真机 20:42:46 就这样：人明明在桌面，报的却是 com.greenpoint.android.mc10086）。
 *   所以起始值取 `dumpsys window` 里的 mFocusedApp —— 它等于系统认的前台应用，含桌面；
 *   events buffer 只作兜底。
 *
 * 用法：
 *   skp_fgwatch [-o /data/local/tmp/skp_fg] [-l /data/local/tmp/skp_fgwatch.log]
 *   skp_fgwatch -v            # 输出版本标记，供投放脚本判断「能不能执行」
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <time.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <sys/file.h>
#include <sys/resource.h>
#include <dirent.h>
#include <poll.h>
#include <errno.h>

static const char *VERSION = "fgwatch-ok v13";

static const char *g_log = "/data/local/tmp/skp_fgwatch.log";

/* 时间：只用来给日志打相对毫秒，不做任何时区换算（见头部坑 3）。 */
static long long mono_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static long long g_t0 = 0;

static void logf_(const char *fmt, ...);

/*
 * ★ 冷启动那 3 秒的真凶（2026-10-30 23:32 实测确认）。
 *
 *   目标应用启动时把 CPU 吃满（类加载 / dex2oat / 首帧），普通优先级的进程
 *   连 `sleep(0.1)` 都被拉成 3 秒 —— 用一个 root shell 每 100ms 打时间戳做对照，
 *   它的 sleep 同样被拉长（13.141 直接跳到 16.098），说明是整机调度问题，
 *   不是我们的 I/O、也不是 logcat 的缓冲（pty 之后已经行缓冲，文件 mtime 与
 *   events 只差 2ms）。
 *
 *   也就是说：**打点是实时的，问题出在「谁来读」**。root 下能做两件事，
 *   让读取链路的进程在别人吃满 CPU 时也能被及时调度：
 *     1) nice -20（CAP_SYS_NICE，root 有）；
 *     2) 放进 top-app 的 cpuset / cpuctl（和前台应用同一组）。
 *   logcat 只处理 events buffer，负载极轻，不会反过来拖慢前台应用。
 */
static void put_in(const char *path, pid_t pid) {
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) return;
    char b[32];
    int n = snprintf(b, sizeof b, "%d", (int) pid);
    if (n > 0) {
        ssize_t w = write(fd, b, (size_t) n);
        (void) w;
    }
    close(fd);
}

static void boost(pid_t pid) {
    setpriority(PRIO_PROCESS, pid, -20);
    put_in("/dev/cpuset/top-app/cgroup.procs", pid);
    put_in("/dev/cpuctl/top-app/cgroup.procs", pid);
}

/*
 * ★★ 把 APK 进程也拉上来（2026-10-30 23:33 实测后加的）。
 *
 *   那一刻的数据非常干净：
 *     · ELF 写 skp_fg 的 mtime = 23:33:42.353
 *     · 系统 events 的 wm_set_resumed_activity = 23:33:42.353   ← 我们已经 0ms
 *     · 但 APK 侧 23:33:45.329 才读到，日志写着 `sleep 拉长 3103ms`
 *   也就是说事件、文件、I/O 全都不是瓶颈了，卡的是**APK 里那个 Java 轮询线程
 *   根本抢不到 CPU**：它是 nice 0，而正在冷启动的前台应用，UI 线程 nice -10、
 *   渲染线程 -16，8 个核被占满时 100ms 的 sleep 会被推到 3 秒。
 *
 *   我们自己提 nice 是没用的（APK 侧没有 CAP_SYS_NICE，Process.setThreadPriority
 *   传负值会被静默拒绝）。但 root 下的 ELF 可以对**别的进程的每个线程**调
 *   setpriority —— 这就是这里在做的事，每次切换前顺手把 APK 拉进 top-app
 *   并把线程提到 -20，让它一被唤醒就能跑。
 *
 *   代价：一次 pidof + 一次 /proc/<pid>/task 遍历，几毫秒，远小于它省下的时间。
 */
static void boost_pid_all_threads(pid_t pid) {
    if (pid <= 0) return;
    char path[64];
    snprintf(path, sizeof path, "/proc/%d/task", (int) pid);
    DIR *d = opendir(path);
    if (d) {
        struct dirent *e;
        int n = 0;
        while ((e = readdir(d)) != NULL && n < 512) {
            if (e->d_name[0] == '.') continue;
            pid_t t = (pid_t) atoi(e->d_name);
            if (t > 0) {
                setpriority(PRIO_PROCESS, t, -20);
                n++;
            }
        }
        closedir(d);
    }
    put_in("/dev/cpuset/top-app/cgroup.procs", pid);
    put_in("/dev/cpuctl/top-app/cgroup.procs", pid);
}

/* 用 pidof 找 APK 进程。用 popen 是为了少写一份 /proc 全表扫描；
 * 只在「切换」这么低频的路径上调用，几毫秒可以接受。 */
static pid_t find_apk_pid(void) {
    FILE *fp = popen("pidof com.v5yn133n3.skp 2>/dev/null", "r");
    if (!fp) return 0;
    char b[64] = "";
    if (fgets(b, sizeof b, fp)) { /* 取第一个 pid */ }
    pclose(fp);
    return (pid_t) atoi(b);
}

/*
 * 节流版：popen 在 CPU 饱和时要几毫秒到几十毫秒，绝不能放在「收到切换 → 写文件」
 * 这条关键路径上。所以改成**平时补**（poll 的 2s 空闲超时里调），切换那一下只管写。
 */
static long long g_boost_at = 0;
static pid_t g_apk_pid = 0;

static void boost_apk(void) {
    pid_t p = find_apk_pid();
    if (p > 0) {
        g_apk_pid = p;
        boost_pid_all_threads(p);
    }
}

/*
 * ★★★★ 真凶：进程被 **cgroup freezer** 冻住了（2026-10-30 23:46 抓到）。
 *
 *   用 /proc/<pid>/cgroup 里的真实路径去读 /sys/fs/cgroup/apps/uid_XXXX/pid_<pid>/cgroup.freeze，
 *   目标应用冷启动那 3 秒里它一直是 **1**（24 次采样 × ~120ms ≈ 2.9s）。
 *
 *   这一条把所有现象都串起来了：
 *     · cgroup.freeze=1 → 整个线程组不让上 CPU，所以 `Thread.sleep(100)` 变 3016ms；
 *     · 阻塞在 pipe_read 的 read 也不会返回（数据到了，进程仍然被冻着）；
 *     · 状态仍是 S（不是 T），所以 SIGSTOP 类的判断全部落空；
 *     · SIGCONT 完全无效 —— freeze 不是信号，是调度器层面的。
 *   oom=-900 也没挡住它（OPPO 的进程管控另有策略）。
 *
 *   解法：切换发生的那一刻由 root 写 0 解冻。写的是我们自己的 cgroup 文件，
 *   系统稍后会按自己的策略再冻，但那时数据早就送到了。
 */
static void unfreeze_pid(pid_t pid) {
    if (pid <= 0) return;
    char path[64], line[256], full[320];
    full[0] = 0;
    snprintf(path, sizeof path, "/proc/%d/cgroup", (int) pid);
    FILE *f = fopen(path, "r");
    if (f) {
        while (fgets(line, sizeof line, f) != NULL) {
            if (strncmp(line, "0::", 3) != 0) continue;   /* v2 统一层级那一行 */
            char *nl = strchr(line, '\n');
            if (nl) *nl = 0;
            snprintf(full, sizeof full, "/sys/fs/cgroup%s/cgroup.freeze", line + 3);
            break;
        }
        fclose(f);
    }
    if (!full[0]) return;
    int fd = open(full, O_WRONLY | O_CLOEXEC);
    if (fd < 0) return;
    ssize_t w = write(fd, "0", 1);
    (void) w;
    close(fd);
}

static void wake_apk(void) {
    if (g_apk_pid <= 0) g_apk_pid = find_apk_pid();
    if (g_apk_pid <= 0) return;
    unfreeze_pid(g_apk_pid);
    kill(g_apk_pid, SIGCONT);   /* 对 frozen 无效，但对 SIGSTOP 有效，留着成本为零 */
}

static void boost_apk_throttled(void) {
    long long now = mono_ms();
    if (now - g_boost_at < 2000) return;
    g_boost_at = now;
    boost_apk();
}

/*
 * ★★ FIFO 通道（v11，2026-10-30 23:36 加）—— 这才是延迟问题的正解。
 *
 *   实测把 APK 进程提到 nice -20 + top-app 之后，`Thread.sleep(100)` 依然被拉成
 *   3026ms；而 ELF 自己阻塞在 read/poll 上，数据一到立刻返回（写文件的 mtime 与
 *   系统 events 只差 2–3ms）。两者唯一的区别是**唤醒方式**：
 *     · ELF  —— poll/read 由内核在数据到达时直接 wake_up；
 *     · APK  —— Thread.sleep 依赖 timer wheel 到期，冷启动那几秒 timer 处理被推迟，
 *               优先级再高也没用（线程根本不在 runqueue 上）。
 *   所以给 APK 一条**阻塞读的 FIFO**：切换时我们往管道写一行，内核直接把读线程
 *   唤醒，不经过任何定时器。文件那条路保留，做初值/降级。
 */
static int g_pipe = -1;
static const char *g_pipe_path = NULL;

static void pipe_open(void) {
    if (g_pipe >= 0 || g_pipe_path == NULL) return;
    /* O_NONBLOCK：读端还没打开时不要卡在这里等（会返回 ENXIO），下轮再试 */
    g_pipe = open(g_pipe_path, O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (g_pipe < 0) {
        static long long last = 0;
        long long now = mono_ms();
        if (now - last > 3000) {
            last = now;
            logf_("pipe open 失败 errno=%d", errno);
        }
    } else {
        logf_("pipe 已连接 fd=%d", g_pipe);
    }
}

static void pipe_send(const char *s) {
    if (g_pipe < 0) pipe_open();
    if (g_pipe < 0) return;
    char b[300];
    int n = snprintf(b, sizeof b, "%s\n", s);
    if (n <= 0) return;
    ssize_t w = write(g_pipe, b, (size_t) n);
    if (w < 0) {
        int e = errno;
        logf_("pipe write 失败 errno=%d", e);
        /* EPIPE = 读端关了（APK 重启）；EAGAIN = 管道满（对方不读，异常） */
        if (e == EPIPE || e == EAGAIN || e == EINTR) {
            close(g_pipe);
            g_pipe = -1;
        }
    }
}

static void logf_(const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    if (n <= 0) return;
    char out[1200];
    int m = snprintf(out, sizeof out, "[%6lld] %s\n", mono_ms() - g_t0, buf);
    if (m <= 0) return;

    /*
     * 日志优先走 stderr（由启动脚本重定向到文件）：write(2) 不经 stdio，
     * 不会有缓冲，也不会因为 /data/local/tmp 的 SELinux label 差异而 open 失败。
     * -l 指定的文件只在 stderr 不可写时兜底。
     */
    if (write(2, out, (size_t) m) > 0) return;
    int fd = open(g_log, O_WRONLY | O_CREAT | O_APPEND, 0666);
    if (fd < 0) return;
    ssize_t w = write(fd, out, (size_t) m);
    (void) w;
    close(fd);
    chmod(g_log, 0666);
}

/* 原子替换：先写 .tmp 再 rename。FileObserver 看 MOVED_TO，APK 侧读不到半截内容。 */
static void put(const char *path, const char *s) {
    char tmp[512];
    snprintf(tmp, sizeof tmp, "%s.tmp", path);
    int fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC, 0666);
    if (fd < 0) {
        logf_("put: open tmp 失败 errno");
        return;
    }
    ssize_t n = write(fd, s, strlen(s));
    (void) n;
    close(fd);
    chmod(tmp, 0666);
    if (rename(tmp, path) != 0) {
        int fd2 = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0666);
        if (fd2 >= 0) {
            ssize_t m = write(fd2, s, strlen(s));
            (void) m;
            close(fd2);
            chmod(path, 0666);
        }
    }
}

/* `... wm_set_resumed_activity: [0,<pkg>/<act>,<reason>]` → pkg */
static int parse_resumed(const char *line, char *dst, size_t cap) {
    const char *p = strstr(line, "wm_set_resumed_activity:");
    if (!p) return 0;
    p = strchr(p, '[');
    if (!p) return 0;
    const char *comma = strchr(p, ',');
    if (!comma) return 0;
    const char *comp = comma + 1;
    const char *slash = strchr(comp, '/');
    if (!slash) return 0;
    size_t n = (size_t) (slash - comp);
    if (n == 0 || n >= cap) return 0;
    memcpy(dst, comp, n);
    dst[n] = 0;
    return strchr(dst, '.') != NULL;       /* 不是包名（空/异常）就丢掉 */
}

/* `mFocusedApp=ActivityRecord{hash u0 <pkg>/<act> tNNN}` → pkg
 * 从 '/' 往回退到空白后的那个 token，兼容 u0 / u10 / 多用户。 */
static int parse_focused(const char *line, char *dst, size_t cap) {
    const char *r = strstr(line, "mFocusedApp=");
    if (!r) return 0;
    const char *slash = strchr(r, '/');
    if (!slash) return 0;
    const char *s = slash;
    while (s > r && s[-1] != ' ' && s[-1] != '{' && s[-1] != '=') s--;
    size_t n = (size_t) (slash - s);
    if (n == 0 || n >= cap) return 0;
    memcpy(dst, s, n);
    dst[n] = 0;
    return strchr(dst, '.') != NULL;
}

/* 设备本地时间 "MM-DD HH:MM:SS.000" —— logcat -T 只认这个格式。
 * 不自己 localtime：glibc 静态链接在 Android 上没有时区数据，会算成 UTC。 */
static int stamp_now(char *dst, size_t cap) {
    dst[0] = 0;
    FILE *fp = popen("date '+%m-%d %H:%M:%S.000' 2>/dev/null", "r");
    if (!fp) return 0;
    int ok = 0;
    if (fgets(dst, (int) cap, fp)) {
        size_t n = strlen(dst);
        while (n && (dst[n - 1] == '\n' || dst[n - 1] == '\r')) dst[--n] = 0;
        ok = (n >= 18);
    }
    pclose(fp);
    if (!ok) dst[0] = 0;
    return ok;
}

/*
 * 起一个 logcat，把它的 stdout 接到 pty 从端。pty 让 libc 认为 stdout 是终端，
 * 于是行缓冲 —— 每写一行就立刻到我们手里，不再有 4KB 攒批。
 * 返回主端 fd（读），pid 通过 out_pid 给回。
 */
static int spawn_logcat_pty(const char *stamp, pid_t *out_pid) {
    int m = posix_openpt(O_RDWR | O_NOCTTY);
    if (m < 0) return -1;
    if (grantpt(m) != 0 || unlockpt(m) != 0) {
        close(m);
        return -1;
    }
    const char *sn = ptsname(m);
    if (!sn) {
        close(m);
        return -1;
    }
    int s = open(sn, O_RDWR | O_NOCTTY);
    if (s < 0) {
        close(m);
        return -1;
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(s);
        close(m);
        return -1;
    }
    if (pid == 0) {
        setsid();
        ioctl(s, TIOCSCTTY, 0);
        dup2(s, 0);
        dup2(s, 1);
        dup2(s, 2);
        if (s > 2) close(s);
        close(m);
        /* ★ 在 exec 之前提权：logcat 这个进程是「事件到达」的最后一环，
         *   它被饿死就等于我们被饿死（见 boost 上面的注释）。 */
        boost(getpid());
        /* argv[0] 故意改成 skp_lg：APK 侧用 `pkill -x skp_lg` 精确清孤儿，
         * 既不会误杀别的 logcat，也不会像 pkill -f 那样匹配到自己那条 shell。 */
        execlp("logcat", "skp_lg",
               "-b", "events",
               "-s", "wm_set_resumed_activity:*",
               "-T", stamp,
               (char *) NULL);
        _exit(127);
    }
    close(s);
    *out_pid = pid;
    return m;
}

int main(int argc, char **argv) {
    g_t0 = mono_ms();
    const char *out = "/data/local/tmp/skp_fg";
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "-v") || !strcmp(argv[i], "--version")) {
            printf("%s\n", VERSION);
            return 0;
        }
        if ((!strcmp(argv[i], "-o") || !strcmp(argv[i], "--out")) && i + 1 < argc) {
            out = argv[++i];
            continue;
        }
        if ((!strcmp(argv[i], "-l") || !strcmp(argv[i], "--log")) && i + 1 < argc) {
            g_log = argv[++i];
            continue;
        }
        if ((!strcmp(argv[i], "-p") || !strcmp(argv[i], "--pipe")) && i + 1 < argc) {
            g_pipe_path = argv[++i];
            continue;
        }
    }
    signal(SIGPIPE, SIG_IGN);
    /* 自己也提一下：阶段 A 的 dumpsys 和整条读取循环都在这个进程里 */
    boost(getpid());

    /*
     * ★ 单实例锁（v6）：多实例会互相踩。
     *   实测 23:09:34 那次「3 秒延迟」不是 pty 的问题，而是旧 v4（popen 管道，
     *   慢 3s）没被 pkill 干净，和新 v5 同时在写同一个 skp_fg —— APK 的
     *   FileObserver 收到的是旧进程的迟到写入，看起来就像新版本也慢。
     *   锁文件常驻不删；进程一死锁自动释放（flock 跟 open file description 走）。
     *
     *   ★ O_CLOEXEC 是必须的：下面 fork 出去跑 logcat，如果不带这个标志，logcat
     *     会继承锁 fd，于是 **ELF 被 kill 掉、孤儿 logcat 还在，锁就永远不放**
     *     （真机 23:12 就是这么卡住的：启动后 3s 读不到包名，
     *      日志里只有一句 "another instance holds the lock, exit"）。
     */
    int lk = open("/data/local/tmp/skp_fgwatch.lock", O_RDWR | O_CREAT | O_CLOEXEC, 0666);
    if (lk >= 0 && flock(lk, LOCK_EX | LOCK_NB) != 0) {
        fprintf(stderr, "another instance holds the lock, exit\n");
        return 0;
    }
    /* 故意不 close(lk)：整个进程生命周期都要持锁，fork 出去的 logcat 也带着它 */

    char line[4096];
    char pkg[256];
    char last[256] = "";

    /* ── 阶段 A：当前前台是谁 ──（mFocusedApp 是真值，含桌面） */
    FILE *f = popen("dumpsys window 2>/dev/null | grep -m1 mFocusedApp", "r");
    if (f) {
        while (fgets(line, sizeof line, f)) {
            if (parse_focused(line, pkg, sizeof pkg)) snprintf(last, sizeof last, "%s", pkg);
        }
        pclose(f);
    }
    /* 兜底：dumpsys 不给就退回 events buffer 的最后一条 */
    if (!last[0]) {
        f = popen("logcat -b events -d -s wm_set_resumed_activity:* 2>/dev/null", "r");
        if (f) {
            while (fgets(line, sizeof line, f)) {
                if (parse_resumed(line, pkg, sizeof pkg)) snprintf(last, sizeof last, "%s", pkg);
            }
            pclose(f);
        }
    }
    logf_("start v13 init=%s", last[0] ? last : "(none)");
    /* 先把 APK 提上来，再写初值：否则「文件已出现」这条通知自己就送不出去 */
    g_boost_at = mono_ms();
    boost_apk();
    if (last[0]) {
        wake_apk();
        pipe_open();
        pipe_send(last);
        put(out, last);
    }

    /* ── 阶段 B：从现在开始跟随（pty 行缓冲，实时） ──
     * 起点取「现在」而不是 buffer 最后一条的时间戳：后者会重放，
     * 把刚刚离开的那个应用再写一遍（人在桌面却报成应用 → 假切换）。 */
    for (;;) {
        char stamp[64];
        if (!stamp_now(stamp, sizeof stamp)) {
            sleep(1);
            continue;
        }
        pid_t pid = 0;
        int fd = spawn_logcat_pty(stamp, &pid);
        if (fd < 0) {
            logf_("spawn 失败，1s 后重试");
            sleep(1);
            continue;
        }
        logf_("logcat 跟随 stamp=%s pid=%d", stamp, (int) pid);

        /* 自己缓冲整行：pty 给的是字节流，一次 read 可能是半行或多行 */
        char acc[8192];
        size_t alen = 0;
        for (;;) {
            /*
             * ★ 用 poll 而不是裸 read：给它 2s 空闲超时，超时就顺手把 APK 进程的
             *   优先级/调度组补一遍（见 boost_pid_all_threads 的说明）。
             *   这样「收到切换 → put 文件」这条路径上没有任何额外开销，
             *   而 APK 那边随时都是被提过的状态。
             */
            struct pollfd pfd;
            pfd.fd = fd;
            pfd.events = POLLIN;
            pfd.revents = 0;
            int pr = poll(&pfd, 1, 2000);
            if (pr == 0) {
                boost_apk_throttled();
                pipe_open();   /* 读端可能刚起来（或刚重启），空闲时补一次 */
                continue;
            }
            if (pr < 0) {
                if (errno == EINTR) continue;
                break;
            }
            char buf[2048];
            ssize_t n = read(fd, buf, sizeof buf);
            if (n <= 0) break;
            for (ssize_t i = 0; i < n; i++) {
                char c = buf[i];
                if (c == '\n') {
                    acc[alen] = 0;
                    if (alen && acc[alen - 1] == '\r') acc[alen - 1] = 0;
                    alen = 0;
                    if (!parse_resumed(acc, pkg, sizeof pkg)) continue;
                    if (!strcmp(pkg, last)) continue;   /* 同包内换 Activity 不算切换 */
                    snprintf(last, sizeof last, "%s", pkg);
                    /* 顺序很重要：先 SIGCONT 把可能被暂停的进程叫醒，再送数据。
                       反过来也行，但先叫醒能让 read 返回后立刻被调度。 */
                    wake_apk();
                    /* FIFO 先写（内核直接唤醒 APK 的读线程），文件后写（初值/降级） */
                    pipe_send(last);
                    put(out, last);
                    logf_("切换 -> %s", last);
                } else if (alen + 1 < sizeof acc) {
                    acc[alen++] = c;
                } else {
                    alen = 0;                     /* 超长行丢掉，别撑爆 */
                }
            }
        }
        close(fd);
        /* 等子进程退出，避免僵尸堆积 */
        int st = 0;
        waitpid(pid, &st, 0);
        logf_("logcat 退出 st=%d，1s 后重连", st);
        sleep(1);
    }
}
