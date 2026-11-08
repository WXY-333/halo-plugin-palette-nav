package cn.litx.palettenav.service;

import cn.litx.palettenav.extension.NavCategory;
import cn.litx.palettenav.extension.NavLink;
import cn.litx.palettenav.extension.NavQuickLink;
import cn.litx.palettenav.extension.NavSearchEngine;
import java.util.Comparator;
import run.halo.app.extension.Extension;

/**
 * Shared predicates and ordering rules for the navigation extensions.
 */
public final class NavEntitySupport {

    private NavEntitySupport() {
    }

    public static boolean alive(Extension extension) {
        return extension != null
            && extension.getMetadata() != null
            && extension.getMetadata().getDeletionTimestamp() == null;
    }

    public static String nameOf(Extension extension) {
        if (extension == null || extension.getMetadata() == null) {
            return "";
        }
        var name = extension.getMetadata().getName();
        return name == null ? "" : name;
    }

    public static int sortOf(Integer sort) {
        return sort == null ? 0 : sort;
    }

    public static boolean isVisible(Boolean visible) {
        return visible == null || visible;
    }

    public static Comparator<NavCategory> categoryOrder() {
        return Comparator
            .comparingInt((NavCategory item) -> sortOf(item.getSpec() == null
                ? null : item.getSpec().getSort()))
            .thenComparing(NavEntitySupport::nameOf);
    }

    public static Comparator<NavLink> linkOrder() {
        return Comparator
            .comparingInt((NavLink item) -> sortOf(item.getSpec() == null
                ? null : item.getSpec().getSort()))
            .thenComparing(NavEntitySupport::nameOf);
    }

    public static Comparator<NavSearchEngine> engineOrder() {
        return Comparator
            .comparingInt((NavSearchEngine item) -> sortOf(item.getSpec() == null
                ? null : item.getSpec().getSort()))
            .thenComparing(NavEntitySupport::nameOf);
    }

    public static Comparator<NavQuickLink> quickLinkOrder() {
        return Comparator
            .comparingInt((NavQuickLink item) -> sortOf(item.getSpec() == null
                ? null : item.getSpec().getSort()))
            .thenComparing(NavEntitySupport::nameOf);
    }
}
