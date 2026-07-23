package org.watermedia.tools;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encapsulates semantic version information and related comparison behaviors.
 */
public class VersionTool implements Comparable<VersionTool> {
    // major.minor.revision ARE REQUIRED; THE .prerelease 4TH COMPONENT IS OPTIONAL (NON-PRERELEASE VERSIONS
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)(?:\\.(\\d+))?[\\-_\\s]?(.*)");

    public final String version;
    public final int major;
    public final int minor;
    public final int revision;
    public final int prerelease;
    public final String extra;

    /**
     * Creates a new version from the given version string.
     *
     * @param version version string
     */
    public VersionTool(final String version) {
        this.version = version == null ? "" : version;
        final Matcher matcher = VERSION_PATTERN.matcher(this.version);
        if (matcher.matches()) {
            this.major = Integer.parseInt(matcher.group(1));
            this.minor = Integer.parseInt(matcher.group(2));
            this.revision = Integer.parseInt(matcher.group(3));
            // GROUP 4 (PRE-RELEASES) IS OPTIONAL
            final String g4 = matcher.group(4);
            this.prerelease = g4 == null || g4.isEmpty() ? -1 : Integer.parseInt(g4);
            // GROUP 5 IS OPTIONAL IN THE PATTERN; EMPTY MATCH MEANS NO QUALIFIER
            final String tail = matcher.group(5);
            this.extra = tail == null || tail.isEmpty() ? null : tail;
        } else {
            this.major = 0;
            this.minor = 0;
            this.revision = 0;
            this.prerelease = 0;
            this.extra = null;
        }
    }

    /**
     * Tests whether this version is at least the required version.
     *
     * @param required required version
     * @return <code>true</code> if this version is at least (equal to or greater than) the required version
     */
    public boolean atLeast(final VersionTool required) {
        return this.compareTo(required) >= 0;
    }

    /**
     * Tests whether this version falls within the inclusive-exclusive range {@code [min, max)}.
     *
     * @param min minimum version (inclusive)
     * @param max maximum version (exclusive)
     * @return <code>true</code> if this version is at least {@code min} and strictly less than {@code max}
     */
    public boolean inRange(final VersionTool min, final VersionTool max) {
        return this.compareTo(min) >= 0 && this.compareTo(max) < 0;
    }

    /**
     * Checks whether this version is 0.0.0(.0), indicating no version or a null version argument.
     *
     * @return <code>true</code> if this version is 0.0.0(.0), <code>false</code> otherwise
     */
    public boolean isZero() {
        return this.major == 0 && this.minor == 0 && this.revision == 0 && (this.prerelease == 0 || this.prerelease == -1) && (this.extra == null || this.extra.isEmpty());
    }

    @Override
    public int compareTo(final VersionTool o) {
        int delta = this.major - o.major;
        if (delta == 0) {
            delta = this.minor - o.minor;
            if (delta == 0) {
                delta = this.revision - o.revision;
                if (delta == 0) {
                    delta = this.prerelease - o.prerelease;
                }
            }
        }
        return delta;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof final VersionTool other)) return false;
        // CONSISTENT WITH compareTo: ONLY major.minor.revision DECIDE EQUALITY; extra IS A BUILD QUALIFIER
        return this.major == other.major && this.minor == other.minor && this.prerelease == other.prerelease && this.revision == other.revision;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.major, this.minor, this.revision, this.prerelease);
    }

    @Override
    public String toString() {
        return this.isZero() ? "<Not Found>" : this.version;
    }
}