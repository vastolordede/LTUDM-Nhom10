package vn.edu.p2p.tracker.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ShareScopePostgresTest {
    @Test void retainsExplicitAndOtherFolderSharesButStopsOrphan() throws Exception {
        String url=System.getenv("P2P_PG_TEST_URL");
        Assumptions.assumeTrue(url!=null&&!url.isBlank(),"PostgreSQL test URL not configured");
        var props=new java.util.Properties();
        String user=System.getenv("P2P_PG_TEST_USER"), password=System.getenv("P2P_PG_TEST_PASSWORD");
        if(user!=null)props.setProperty("user",user); if(password!=null)props.setProperty("password",password);
        UUID owner=UUID.randomUUID(), other=UUID.randomUUID();
        // Temporary tables shadow real tables, only for this connection. No persistent data modified.
        try(var c=DriverManager.getConnection(url,props); var st=c.createStatement()) {
            st.execute("CREATE TEMP TABLE peer_files(peer_id uuid,file_id bigint,is_sharing boolean,updated_at timestamp)");
            st.execute("CREATE TEMP TABLE group_files(group_id bigint,file_id bigint)");
            st.execute("CREATE TEMP TABLE file_groups(group_id bigint,owner_peer_id uuid)");
            st.execute("CREATE TEMP TABLE share_events(share_event_id bigint GENERATED ALWAYS AS IDENTITY,peer_id uuid,file_id bigint,event_type varchar(16))");
            try(var ins=c.prepareStatement("INSERT INTO peer_files VALUES (?, ?, true, CURRENT_TIMESTAMP)")) {
                for(long id=1;id<=3;id++){ins.setObject(1,owner);ins.setLong(2,id);ins.executeUpdate();}
                ins.setObject(1,other);ins.setLong(2,1);ins.executeUpdate();
            }
            try(var ins=c.prepareStatement("INSERT INTO file_groups VALUES (7,?)")){ins.setObject(1,owner);ins.executeUpdate();}
            st.execute("INSERT INTO group_files VALUES(7,2)");
            try(var ins=c.prepareStatement("INSERT INTO share_events(peer_id,file_id,event_type) VALUES (?,3,'SHARE_START')")){ins.setObject(1,owner);ins.executeUpdate();}
            assertTrue(ShareScopeSql.stopIfUnreferenced(c,owner,1));
            assertFalse(ShareScopeSql.stopIfUnreferenced(c,owner,2));
            assertFalse(ShareScopeSql.stopIfUnreferenced(c,owner,3));
            try(var q=c.prepareStatement("SELECT is_sharing FROM peer_files WHERE peer_id=? AND file_id=1")){q.setObject(1,other);try(var rs=q.executeQuery()){assertTrue(rs.next());assertTrue(rs.getBoolean(1));}}
            st.execute("DELETE FROM group_files WHERE group_id=7");
            assertTrue(ShareScopeSql.stopIfUnreferenced(c,owner,2));
        }
    }
}
