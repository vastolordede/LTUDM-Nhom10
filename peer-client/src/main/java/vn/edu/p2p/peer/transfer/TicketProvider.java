package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.dto.FileSourceInfo;

import java.io.IOException;

@FunctionalInterface
public interface TicketProvider {
    String refresh(long fileId, FileSourceInfo source) throws IOException;
}
