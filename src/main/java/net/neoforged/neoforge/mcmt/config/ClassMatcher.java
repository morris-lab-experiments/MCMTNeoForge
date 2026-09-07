/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.mcmt.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * One configured list of ticking classes, as an "is this class in it?" question.
 *
 * <p>An entry is either a fully-qualified class name or a wildcard pattern. Naming classes one at a time is
 * exact but impractical for the case this exists to serve: an owner who has found that some mod misbehaves
 * under MCMT wants to say so about the mod, not to enumerate its two hundred block entities. So:
 *
 * <ul>
 * <li>{@code com.example.mod.BlockEntityFoo} — that class, and only it.
 * <li>{@code com.example.mod.*} — every class directly in that package.
 * <li>{@code com.example.mod.**} — that package and everything beneath it.
 * </ul>
 *
 * <p>Matching is on the class's own name only; it is not inherited, because a filter's answer is cached per
 * concrete class and a subclass in a different package is a different question. An entry with no wildcard in
 * it that does not resolve to a loaded class is kept as {@linkplain #unresolved() unresolved} rather than
 * dropped, so that a config shared between a modded and a vanilla instance survives a save.
 *
 * <p>Instances are consulted from {@link net.neoforged.neoforge.mcmt.serdes.filter.SerDesFilter filters}, which
 * run on cache misses only, so a regex match here is off the hot path.
 */
public final class ClassMatcher {
    private static final Logger LOGGER = LogManager.getLogger();

    /** Entries that resolved to a class present in this environment. */
    private final Set<Class<?>> classes = ConcurrentHashMap.newKeySet();

    /** Wildcard entries, in their configured form, so {@link #toConfigList()} can write them back verbatim. */
    private final List<String> patterns = new CopyOnWriteArrayList<>();

    /** Entries that named a class this environment does not have. Preserved across a save. */
    private final List<String> unresolved = new CopyOnWriteArrayList<>();

    /** {@link #patterns} compiled into one alternation, or null when there are none. */
    private volatile Pattern regex;

    /** An empty matcher, to be filled by {@link #load}. */
    public ClassMatcher() {}

    /**
     * Replaces this matcher's contents with {@code entries}.
     *
     * <p>Called from {@link MCMTConfig#bake()}, which holds the config lock, so the three collections are not
     * written concurrently — but they are read concurrently by filters throughout, which is why they are
     * concurrent collections and {@link #regex} is volatile rather than this being a wholesale swap.
     */
    void load(List<? extends String> entries) {
        this.classes.clear();
        this.patterns.clear();
        this.unresolved.clear();

        List<String> compiled = new ArrayList<>();
        for (String entry : entries) {
            if (entry.indexOf('*') >= 0) {
                String translated = translate(entry);
                if (translated == null) {
                    LOGGER.warn("MCMT: ignoring malformed class pattern '{}' in the config", entry);
                    continue;
                }
                this.patterns.add(entry);
                compiled.add(translated);
                continue;
            }
            try {
                this.classes.add(Class.forName(entry, false, ClassMatcher.class.getClassLoader()));
            } catch (ClassNotFoundException | LinkageError e) {
                LOGGER.debug("MCMT: config lists class {}, which is not present in this environment", entry);
                this.unresolved.add(entry);
            }
        }

        this.regex = compiled.isEmpty() ? null : Pattern.compile(String.join("|", compiled));
    }

    /**
     * Turns a wildcard entry into a regex matching a whole class name, or null if it will not compile.
     *
     * <p>{@code *} stops at a package separator and {@code **} crosses it, so {@code com.example.*} does not
     * pick up {@code com.example.sub.Thing} while {@code com.example.**} does. Nested classes are named with
     * {@code $} rather than a dot, so a single {@code *} matches those too — {@code com.example.Foo*} covers
     * {@code com.example.Foo$Ticker}, which is where a good deal of mod tick code actually lives.
     */
    @Nullable
    private static String translate(String pattern) {
        StringBuilder out = new StringBuilder(pattern.length() + 8);
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c != '*') {
                literal.append(c);
                continue;
            }
            if (!literal.isEmpty()) {
                out.append(Pattern.quote(literal.toString()));
                literal.setLength(0);
            }
            if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                out.append(".*");
                i++;
            } else {
                out.append("[^.]*");
            }
        }
        if (!literal.isEmpty()) {
            out.append(Pattern.quote(literal.toString()));
        }

        String translated = out.toString();
        try {
            // Compiled and thrown away: the alternation this joins is compiled once, and a single bad entry
            // must not take the rest of the list down with it.
            Pattern.compile(translated);
        } catch (PatternSyntaxException e) {
            return null;
        }
        return translated;
    }

    /** Whether {@code type} is named by this list, exactly or by pattern. */
    public boolean matches(Class<?> type) {
        if (this.classes.contains(type)) {
            return true;
        }
        Pattern p = this.regex;
        return p != null && p.matcher(type.getName()).matches();
    }

    /**
     * Adds a resolved class to this list, as {@code AutoFilter}'s demotions are folded in.
     *
     * @return true if it was not already named — by pattern as well as exactly, so that persisting a demotion
     *         of a class some pattern already covers does not clutter the config file with a redundant entry
     */
    public boolean add(Class<?> type) {
        if (matches(type)) {
            return false;
        }
        return this.classes.add(type);
    }

    /** Entries that named a class absent from this environment. */
    public List<String> unresolved() {
        return List.copyOf(this.unresolved);
    }

    /** True when nothing is configured, so {@code /mcmt stats} can say "none" rather than print an empty list. */
    public boolean isEmpty() {
        return this.classes.isEmpty() && this.patterns.isEmpty() && this.unresolved.isEmpty();
    }

    /** How many entries this list holds, counting patterns and unresolved names. */
    public int size() {
        return this.classes.size() + this.patterns.size() + this.unresolved.size();
    }

    /**
     * This list rendered back to config entries: resolved class names, wildcard patterns as configured, and
     * names that did not resolve. Sorted, so that a {@code /mcmt save} does not reshuffle the file.
     */
    public List<String> toConfigList() {
        Set<String> out = new TreeSet<>();
        for (Class<?> c : this.classes) {
            out.add(c.getName());
        }
        out.addAll(this.patterns);
        out.addAll(this.unresolved);
        return List.copyOf(out);
    }
}
