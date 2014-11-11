package nbdfdb;

import com.foundationdb.async.Future;
import com.foundationdb.async.PartialFunction;
import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;
import nbdfdb.NBD.Command;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static nbdfdb.NBD.EMPTY_124;
import static nbdfdb.NBD.NBD_FLAG_HAS_FLAGS;
import static nbdfdb.NBD.NBD_FLAG_SEND_FLUSH;
import static nbdfdb.NBD.NBD_OK_BYTES;
import static nbdfdb.NBD.NBD_REPLY_MAGIC_BYTES;
import static nbdfdb.NBD.NBD_REQUEST_MAGIC;

/**
* Created by sam on 11/9/14.
*/
public class NBDVolumeServer implements Runnable {

  private final Logger log;

  private final DataInputStream in;
  private final DataOutputStream out;
  private final String exportName;
  private final Storage storage;

  public NBDVolumeServer(String exportName, DataInputStream in, DataOutputStream out) throws IOException {
    this.exportName = exportName;
    log = Logger.getLogger("NDB: " + exportName);
    storage = new FDBStorage(exportName);
    log.info("Mounting " + exportName + " of size " + storage.size());
    storage.connect();
    this.in = in;
    this.out = out;
  }

  private void writeReplyHeaderAndFlush(long handle) throws IOException {
    synchronized (out) {
      out.write(NBD_REPLY_MAGIC_BYTES);
      out.write(NBD_OK_BYTES);
      out.writeLong(handle);
      out.flush();
    }
  }

  @Override
  public void run() {
    try {
      out.writeLong(storage.size());
      out.writeShort(NBD_FLAG_HAS_FLAGS | NBD_FLAG_SEND_FLUSH);
      out.write(EMPTY_124);
      out.flush();

      while (true) {
        int requestMagic = in.readInt();// MAGIC
        if (requestMagic != NBD_REQUEST_MAGIC) {
          throw new IllegalArgumentException("Invalid magic number for request: " + requestMagic);
        }
        Command requestType = Command.values()[in.readInt()];
        long handle = in.readLong();
        UnsignedLong offset = UnsignedLong.fromLongBits(in.readLong());
        UnsignedInteger requestLength = UnsignedInteger.fromIntBits(in.readInt());
        if (requestLength.longValue() > Integer.MAX_VALUE) {
          // We could ultimately support this but it isn't common by any means
          throw new IllegalArgumentException("Failed to read, length too long: " + requestLength);
        }
        switch (requestType) {
          case READ: {
            byte[] buffer = new byte[requestLength.intValue()];
            log.info("Reading " + buffer.length + " from " + offset);
            Future<Void> read = storage.read(buffer, offset.intValue());
            read.map((PartialFunction<Void, Object>) $ -> {
              synchronized (out) {
                out.write(NBD_REPLY_MAGIC_BYTES);
                out.write(NBD_OK_BYTES);
                out.writeLong(handle);
                out.write(buffer);
                out.flush();
              }
              return null;
            });
            break;
          }
          case WRITE: {
            byte[] buffer = new byte[requestLength.intValue()];
            in.readFully(buffer);
            log.info("Writing " + buffer.length + " to " + offset);
            Future<Void> write = storage.write(buffer, offset.intValue());
            write.map((PartialFunction<Void, Object>) $ -> {
              writeReplyHeaderAndFlush(handle);
              return null;
            });
            break;
          }
          case DISCONNECT:
            log.info("Disconnecting " + exportName);
            storage.disconnect();
            return;
          case FLUSH:
            log.info("Flushing");
            long start = System.currentTimeMillis();
            storage.flush().map((PartialFunction<Void, Object>) $ -> {
              writeReplyHeaderAndFlush(handle);
              log.info("Flush complete: " + (System.currentTimeMillis() - start) + "ms");
              return null;
            });
            break;
          case TRIM:
            log.warning("Trim unimplemented");
            writeReplyHeaderAndFlush(handle);
            break;
          case CACHE:
            log.warning("Cache unimplemented");
            break;
        }
      }
    } catch (Exception e) {
      log.log(Level.SEVERE, "Unmounting volume " + exportName, e);
    }
  }
}
