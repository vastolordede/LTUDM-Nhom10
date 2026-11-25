package vn.edu.p2p.peer.view;

import java.time.*;
import java.util.*;
import java.util.function.*;

/** Shared history filtering: inclusive local dates, stable sorting before pagination. */
public record HistoryFilter(String query, Order order, Period period, LocalDate from, LocalDate to) {
    public enum Order {
        NEWEST("Mới nhất"), OLDEST("Cũ nhất"), NAME_ASC("Tên A–Z"), NAME_DESC("Tên Z–A"), SIZE_ASC("Dung lượng tăng dần"), SIZE_DESC("Dung lượng giảm dần");
        private final String title; Order(String title){this.title=title;} public String toString(){return title;}
    }
    public enum Period {
        ALL("Tất cả thời gian"), WEEK("7 ngày qua"), MONTH("1 tháng qua"), HALF_YEAR("6 tháng qua"), YEAR("1 năm qua"), CUSTOM("Từ ngày – đến ngày");
        private final String title; Period(String title){this.title=title;} public String toString(){return title;}
    }
    public HistoryFilter {
        query=Objects.requireNonNullElse(query,"").strip().toLowerCase(Locale.ROOT);
        order=Objects.requireNonNullElse(order,Order.NEWEST);
        period=Objects.requireNonNullElse(period,Period.ALL);
    }
    public boolean valid(){return period!=Period.CUSTOM || from==null || to==null || !from.isAfter(to);}
    public boolean matchesName(String name){return Objects.requireNonNullElse(name,"").toLowerCase(Locale.ROOT).contains(query);}
    public boolean matchesTime(long ms, ZonedDateTime now) {
        if(!valid()) return false;
        if(period==Period.ALL) return true;
        if(ms<=0) return false;
        Instant instant=Instant.ofEpochMilli(ms);
        if(period==Period.CUSTOM) {
            LocalDate date=instant.atZone(now.getZone()).toLocalDate();
            return (from==null||!date.isBefore(from)) && (to==null||!date.isAfter(to));
        }
        ZonedDateTime start=switch(period){case WEEK->now.minusDays(7);case MONTH->now.minusMonths(1);case HALF_YEAR->now.minusMonths(6);case YEAR->now.minusYears(1);default->throw new IllegalStateException();};
        return !instant.isBefore(start.toInstant()) && !instant.isAfter(now.toInstant());
    }
    public <T> Comparator<T> comparator(Function<T,String> name, ToLongFunction<T> size, ToLongFunction<T> date, Function<T,String> key) {
        Comparator<T> names=Comparator.comparing(x->Objects.requireNonNullElse(name.apply(x),""),String.CASE_INSENSITIVE_ORDER);
        Comparator<T> sizes=Comparator.comparingLong(size), dates=Comparator.comparingLong(date);
        Comparator<T> first=switch(order){case NEWEST->dates.reversed();case OLDEST->dates;case NAME_ASC->names;case NAME_DESC->names.reversed();case SIZE_ASC->sizes;case SIZE_DESC->sizes.reversed();};
        return first.thenComparing(dates.reversed()).thenComparing(names).thenComparing(x->Objects.requireNonNullElse(key.apply(x),""));
    }
}
