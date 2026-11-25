package vn.edu.p2p.tracker.catalog;

/** The deployed Tracker is one process. Serialize catalog changes and ticket issuance
 * so a source lookup begun after a successful stop cannot issue a ticket for it. */
public final class CatalogMutationLock {
    public static final Object LOCK = new Object();
    private CatalogMutationLock() {}
}
