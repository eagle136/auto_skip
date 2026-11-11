import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * Root-side bridge for the Oplus "recent task lock" list.
 *
 * The list is owned by system_server and only writable through
 * com.oplus.util.OplusCommonConfig, whose caller must hold the signature
 * permission oplus.permission.OPLUS_COMPONENT_SAFE. A normal app cannot get
 * it, so OemLock runs this class with app_process under su (uid 0).
 *
 * Usage: SkpLock <package> add|remove|read [user]
 * Prints SKP_LOCK_OK / SKP_LOCK_FAIL / SKP_LOCK_PRESENT / SKP_LOCK_ABSENT.
 */
public final class SkpLock {
    private static final String KEY = "recent_lock_list";

    public static void main(String[] args) {
        String pkg = args.length > 0 ? args[0] : "";
        String action = args.length > 1 ? args[1] : "read";
        int user = 0;
        try {
            if (args.length > 2) user = Integer.parseInt(args[2]);
        } catch (NumberFormatException ignored) {
        }
        if (pkg.isEmpty()) {
            System.out.println("SKP_LOCK_FAIL no-pkg");
            return;
        }
        try {
            Class<?> cls = Class.forName("com.oplus.util.OplusCommonConfig");
            Object inst = cls.getMethod("getInstance").invoke(null);
            Method get = cls.getMethod("getConfigInfoAsUser", String.class, int.class, int.class);
            Method put = cls.getMethod("putConfigInfoAsUser", String.class, Bundle.class,
                    int.class, int.class);

            ArrayList<String> list = read(get, inst, user);
            String entry = pkg + "#" + user;
            boolean want = "add".equals(action);
            if ("add".equals(action)) {
                if (!list.contains(entry)) list.add(entry);
            } else if ("remove".equals(action)) {
                list.remove(entry);
            }

            if (!"read".equals(action)) {
                Bundle out = new Bundle();
                out.putStringArrayList(KEY, list);
                Object ok = put.invoke(inst, KEY, out, 1, user);
                if (!Boolean.TRUE.equals(ok)) {
                    System.out.println("SKP_LOCK_FAIL put=false");
                    return;
                }
            }

            ArrayList<String> now = read(get, inst, user);
            boolean present = now.contains(entry);
            System.out.println("list=" + now);
            if ("read".equals(action)) {
                System.out.println(present ? "SKP_LOCK_PRESENT" : "SKP_LOCK_ABSENT");
            } else if (present == want) {
                System.out.println("SKP_LOCK_OK");
            } else {
                System.out.println("SKP_LOCK_FAIL readback=" + present);
            }
        } catch (Throwable t) {
            System.out.println("SKP_LOCK_FAIL " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static ArrayList<String> read(Method get, Object inst, int user) throws Exception {
        Bundle b = (Bundle) get.invoke(inst, KEY, 1, user);
        ArrayList<String> list = b == null ? null : b.getStringArrayList(KEY);
        return list == null ? new ArrayList<String>() : new ArrayList<String>(list);
    }
}
