import app.quantusnode.node.StringsTable;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mechanical i18n check: every language table carries the
 *  same key set as the Japanese source, and the format
 *  placeholders of every entry match the source's. */
public final class StringsCheckMain {
    private static Set<String> placeholders(String v) {
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("%\\d+\\$[a-z]").matcher(v);
        while (m.find()) out.add(m.group());
        return out;
    }

    public static void main(String[] args) {
        Set<String> source = StringsTable.table("ja").keySet();
        int missing = 0;
        int mismatched = 0;
        for (String tag : StringsTable.TAGS) {
            Map<String, String> t = StringsTable.table(tag);
            for (String k : source) {
                if (!t.containsKey(k)) {
                    System.out.println("MISSING " + tag + " " + k);
                    missing++;
                } else if (!placeholders(t.get(k))
                        .equals(placeholders(
                                StringsTable.table("ja").get(k)))) {
                    System.out.println("PLACEHOLDER " + tag + " " + k);
                    mismatched++;
                }
            }
            for (String k : t.keySet()) {
                if (!source.contains(k)) {
                    System.out.println("EXTRA " + tag + " " + k);
                    missing++;
                }
            }
        }
        System.out.println("keys=" + source.size()
                + " languages=" + StringsTable.TAGS.length
                + " missing=" + missing
                + " placeholderMismatch=" + mismatched);
        if (missing > 0 || mismatched > 0) System.exit(1);
    }
}
