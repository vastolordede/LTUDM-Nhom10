package vn.edu.p2p.common.dto;

/** Explicit whitelist of sorting modes for File and Folder search. */
public enum DiscoverySort {
    POPULARITY("Tải nhiều nhất"),
    NAME_ASC("Tên A-Z"),
    NAME_DESC("Tên Z-A"),
    DATE_NEWEST("Mới nhất"),
    DATE_OLDEST("Cũ nhất"),
    SIZE_ASC("Dung lượng tăng"),
    SIZE_DESC("Dung lượng giảm");

    private final String label;
    DiscoverySort(String label) { this.label = label; }
    @Override public String toString() { return label; }
}
