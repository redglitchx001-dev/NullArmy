package redglitchx.nullarmy.core.ledger;

/**
 * A material identifier.
 *
 * <p>{@code core} cannot reference Bukkit's {@code Material} enum (ADR-004), so
 * items are identified by their vanilla namespaced id. The adapter is
 * responsible for mapping these to real server materials and rejecting any id
 * the running version does not know.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class ItemId {

    public static final ItemId AIR = new ItemId("minecraft", "air");

    private final String namespace;
    private final String path;

    private ItemId(String namespace, String path) {
        this.namespace = namespace;
        this.path = path;
    }

    /** Parses {@code "minecraft:diamond"} or bare {@code "diamond"} (assumes minecraft). */
    public static ItemId of(String namespaced) {
        if (namespaced == null || namespaced.isEmpty()) {
            throw new IllegalArgumentException("item id must not be empty");
        }
        int colon = namespaced.indexOf(':');
        if (colon < 0) {
            return of("minecraft", namespaced);
        }
        return of(namespaced.substring(0, colon), namespaced.substring(colon + 1));
    }

    public static ItemId of(String namespace, String path) {
        if (namespace == null || namespace.isEmpty()) {
            throw new IllegalArgumentException("namespace must not be empty");
        }
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path must not be empty");
        }
        return new ItemId(namespace, path);
    }

    public String namespace() { return namespace; }
    public String path() { return path; }

    public String namespaced() { return namespace + ":" + path; }

    public boolean isAir() { return "minecraft".equals(namespace) && "air".equals(path); }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ItemId)) {
            return false;
        }
        ItemId other = (ItemId) o;
        return namespace.equals(other.namespace) && path.equals(other.path);
    }

    @Override
    public int hashCode() {
        return 31 * namespace.hashCode() + path.hashCode();
    }

    @Override
    public String toString() { return namespaced(); }
}
