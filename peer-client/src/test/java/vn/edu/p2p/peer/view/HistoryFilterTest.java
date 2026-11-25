package vn.edu.p2p.peer.view;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class HistoryFilterTest {
    private static final ZonedDateTime NOW=ZonedDateTime.of(2026,11,25,16,0,0,0,ZoneId.of("Asia/Ho_Chi_Minh"));
    private static long ms(ZonedDateTime date){return date.toInstant().toEpochMilli();}
    record Row(String name,long size,long date,String id){}
    @Test void datesAreInclusiveInLocalTime(){
        var f=new HistoryFilter("",HistoryFilter.Order.NEWEST,HistoryFilter.Period.CUSTOM,LocalDate.of(2026,11,24),LocalDate.of(2026,11,25));
        assertTrue(f.matchesTime(ms(NOW.minusDays(1).toLocalDate().atStartOfDay(NOW.getZone())),NOW));
        assertTrue(f.matchesTime(ms(NOW.toLocalDate().plusDays(1).atStartOfDay(NOW.getZone()).minusNanos(1)),NOW));
        assertFalse(f.matchesTime(ms(NOW.toLocalDate().plusDays(1).atStartOfDay(NOW.getZone())),NOW));
        assertFalse(new HistoryFilter("",null,HistoryFilter.Period.CUSTOM,NOW.toLocalDate(),NOW.toLocalDate().minusDays(1)).valid());
    }
    @Test void presetsAndUnknownTimes(){
        for(var period:List.of(HistoryFilter.Period.WEEK,HistoryFilter.Period.MONTH,HistoryFilter.Period.HALF_YEAR,HistoryFilter.Period.YEAR)){
            var start=switch(period){case WEEK->NOW.minusDays(7);case MONTH->NOW.minusMonths(1);case HALF_YEAR->NOW.minusMonths(6);case YEAR->NOW.minusYears(1);default->throw new IllegalStateException();};
            var f=new HistoryFilter("",null,period,null,null);
            assertTrue(f.matchesTime(ms(start),NOW));assertFalse(f.matchesTime(ms(start)-1,NOW));assertFalse(f.matchesTime(ms(NOW)+1,NOW));assertFalse(f.matchesTime(0,NOW));
        }
    }
    @Test void filterAndSortAcrossAllPages(){
        var f=new HistoryFilter("  DOC ",HistoryFilter.Order.SIZE_DESC,HistoryFilter.Period.ALL,null,null);
        var rows=IntStream.range(0,315).mapToObj(i->new Row(i%3==0?"doc"+i:"other"+i,i,ms(NOW),""+i)).toList();
        var result=rows.stream().filter(r->f.matchesName(r.name())).sorted(f.comparator(Row::name,Row::size,Row::date,Row::id)).toList();
        assertEquals(105,result.size());assertEquals(312L,result.getFirst().size());assertEquals(12L,result.get(100).size());assertEquals(0L,result.getLast().size());
    }
}
