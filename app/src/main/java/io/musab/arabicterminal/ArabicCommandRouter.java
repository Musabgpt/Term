package io.musab.arabicterminal;

import java.util.Locale;

/** نحو عربي محلي؛ لا يستدعي أي خدمة شبكة أو نموذج ذكاء اصطناعي. */
public final class ArabicCommandRouter {
    public enum Kind { SHELL, HELP, PHONE, BATTERY, MEMORY, STORAGE, IDENTITY,
        SETTINGS, CHOOSE_FILE, CLEAR, ROOT, UNKNOWN, CONFIRM_DELETE }
    public static final class Parsed {
        public final Kind kind;
        public final String command;
        public final String description;
        Parsed(Kind type, String cmd, String description) {
            this.kind = type; this.command = cmd; this.description = description;
        }
    }
    private ArabicCommandRouter() {}
    private static Parsed action(Kind type) { return new Parsed(type, "", ""); }
    private static Parsed shell(String cmd) { return new Parsed(Kind.SHELL, cmd, cmd); }
    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
    private static String normalize(String s) {
        return s.replaceAll("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]", "")
                .replace('أ','ا').replace('إ','ا').replace('آ','ا')
                .replace('ى','ي').toLowerCase(Locale.ROOT);
    }
    private static boolean eq(String s, String... words) {
        for (String word : words) if (s.equals(word)) return true;
        return false;
    }
    private static Parsed pathCommand(String normalized, String original, String prefix,
                                      String shellPrefix, boolean destructive) {
        if (!normalized.startsWith(prefix) || normalized.length() <= prefix.length()) return null;
        String path = original.substring(prefix.length()).trim();
        if (path.isEmpty()) return action(Kind.UNKNOWN);
        return new Parsed(destructive ? Kind.CONFIRM_DELETE : Kind.SHELL,
                shellPrefix + quote(path), path);
    }
    public static Parsed parse(String value) {
        if (value == null || value.trim().isEmpty()) return action(Kind.UNKNOWN);
        String original = value.trim().replaceAll("\\s+", " ");
        String s = normalize(original);
        if (eq(s, "مساعدة","ساعدني","الاوامر","دليل","؟")) return action(Kind.HELP);
        if (eq(s, "الهاتف","الجهاز","معلومات الهاتف","معلومات الجهاز")) return action(Kind.PHONE);
        if (eq(s, "البطارية","حالة البطارية","بطارية")) return action(Kind.BATTERY);
        if (eq(s, "الرام","الذاكرة","الذاكره","استخدام الذاكرة")) return action(Kind.MEMORY);
        if (eq(s, "التخزين","المساحة","مساحة التخزين")) return action(Kind.STORAGE);
        if (eq(s, "الهوية","من انا","صلاحياتي")) return action(Kind.IDENTITY);
        if (eq(s, "الاعدادات","افتح الاعدادات")) return action(Kind.SETTINGS);
        if (eq(s, "اختر ملف","استيراد ملف","تصفح الملفات")) return action(Kind.CHOOSE_FILE);
        if (eq(s, "مسح","نظف الشاشة","امسح الشاشة")) return action(Kind.CLEAR);
        if (eq(s, "روت","جذر","صلاحيات الجذر")) return action(Kind.ROOT);
        if (eq(s, "الملفات","اعرض الملفات","قائمة الملفات")) return shell("ls -la");
        if (eq(s, "اين انا","مكاني","المجلد الحالي")) return shell("pwd");
        if (eq(s, "العمليات","البرامج الجارية")) return shell("ps -A");
        if (eq(s, "التاريخ","الوقت","الساعة")) return shell("date");
        if (eq(s, "النواة","اصدار النظام")) return shell("uname -a");
        if (eq(s, "الشبكة","واجهات الشبكة")) return shell("ip addr");
        if (eq(s, "الملفات المخفية")) return shell("ls -la");
        if (eq(s, "مساعدة النظام")) return shell("help");
        String[][] verbs = {
            {"اذهب الي ", "cd "}, {"ادخل ", "cd "},
            {"اقرا ", "cat "}, {"اعرض ملف ", "cat "},
            {"انشئ مجلد ", "mkdir -p "}, {"انشئ ملف ", "touch "},
            {"انسخ ملف ", "cp "}, {"احذف ملف ", "rm "},
            {"احذف مجلد ", "rmdir "}, {"اطبع ", "printf '%s\\n' "}
        };
        for (String[] verb : verbs) {
            if (verb[0].equals("انسخ ملف ")) continue; // requires two operands; never guess.
            Parsed parsed = pathCommand(s, original, verb[0], verb[1],
                    verb[0].startsWith("احذف"));
            if (parsed != null) return parsed;
        }
        if (original.startsWith("!")) {
            String cmd = original.substring(1).trim();
            return cmd.isEmpty() ? action(Kind.UNKNOWN) : shell(cmd);
        }
        if (original.matches("(?s)^[A-Za-z_./~$].*")) return shell(original);
        return action(Kind.UNKNOWN);
    }
}
