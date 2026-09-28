package org.elci.syntax;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** "Did you mean" hints for unknown names (ADR-011 section 4). */
public final class Suggestions {
    private Suggestions() {}

    /**
     * Up to three of {@code candidates} close to {@code name}: the same ignoring case, or within
     * an edit distance that grows with the name's length (a swap of two neighbours is one edit).
     * A qualified candidate also matches by its last segment, so {@code doubel} finds
     * {@code Helper.double}.
     */
    public static List<String> closest(String name, Collection<String> candidates) {
        // Under one module, only the last part can be misspelt: String.toInt is not String.join.
        int dot = name.lastIndexOf('.');
        String module = dot < 0 ? null : name.substring(0, dot + 1), last = name.substring(dot + 1);
        // Under a module the candidates are few, so even a short name may be one edit off: List.mp.
        int limit = module == null ? limit(name) : Math.max(1, limit(last));
        record Scored(String candidate, int distance) {}
        var scored = new ArrayList<Scored>();
        for (String candidate : new TreeSet<>(candidates)) {
            if (candidate.equals(name) || candidate.isEmpty() || candidate.contains("$")) continue;
            int distance;
            if (module != null && candidate.startsWith(module) && candidate.indexOf('.', module.length()) < 0)
                distance = distance(last, candidate.substring(module.length()));
            else if (module != null)
                distance = distance(name, candidate);
            else {
                distance = distance(name, candidate);
                if (candidate.contains("."))
                    distance = Math.min(distance, distance(name, candidate.substring(candidate.lastIndexOf('.') + 1)));
            }
            if (candidate.equalsIgnoreCase(name)) distance = 0;
            if (distance <= limit) scored.add(new Scored(candidate, distance));
        }
        scored.sort(Comparator.comparingInt(Scored::distance));
        return scored.stream().limit(3).map(Scored::candidate).toList();
    }

    /** How many edits a name of this length may be from a suggestion. */
    private static int limit(String name) {
        return name.length() <= 2 ? 0 : name.length() <= 5 ? 1 : 2;
    }

    /** A "did you mean" hint, or none. */
    public static List<String> hint(String name, Collection<String> candidates) {
        var close = closest(name, candidates);
        if (close.isEmpty()) return List.of();
        var quoted = close.stream().map(c -> "`" + c + "`").toList();
        String choices = quoted.size() == 1 ? quoted.getFirst()
                : String.join(", ", quoted.subList(0, quoted.size() - 1)) + " or " + quoted.getLast();
        return List.of("did you mean " + choices + "?");
    }

    /** Optimal string alignment distance, ignoring case. */
    static int distance(String a, String b) {
        String x = a.toLowerCase(Locale.ROOT), y = b.toLowerCase(Locale.ROOT);
        if (Math.abs(x.length() - y.length()) > 2) return Integer.MAX_VALUE / 2;
        int[][] d = new int[x.length() + 1][y.length() + 1];
        for (int i = 0; i <= x.length(); i++) d[i][0] = i;
        for (int j = 0; j <= y.length(); j++) d[0][j] = j;
        for (int i = 1; i <= x.length(); i++)
            for (int j = 1; j <= y.length(); j++) {
                int cost = x.charAt(i - 1) == y.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && x.charAt(i - 1) == y.charAt(j - 2) && x.charAt(i - 2) == y.charAt(j - 1))
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
            }
        return d[x.length()][y.length()];
    }
}
