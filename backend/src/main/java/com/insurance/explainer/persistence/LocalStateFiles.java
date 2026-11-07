package com.insurance.explainer.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Single-process private local storage, never served over HTTP or put in images.
 * Atomic replacement + payload checksum. No credentials or exception bodies logged.
 * A named Docker volume is required to survive container recreation.
 */
public final class LocalStateFiles {
    private static final long MAX_BYTES=64_000_000;
    private final Path directory;
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);

    public static final class StorageFailure extends RuntimeException {
        public StorageFailure() { super("LOCAL_CHECKPOINT_STORAGE_ERROR"); }
    }

    public static LocalStateFiles optional(String root, String namespace) {
        return root==null || root.isBlank() ? null : new LocalStateFiles(Path.of(root).resolve(namespace));
    }

    public LocalStateFiles(Path directory) {
        this.directory=directory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.directory);
            if(Files.isSymbolicLink(this.directory))throw new StorageFailure();
        } catch(Exception e) { throw new StorageFailure(); }
    }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(Exception e) { throw new StorageFailure(); }
    }
    private Path file(String id) {return directory.resolve(digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8))+".json");}
    public JsonNode tree(Object value) {return mapper.valueToTree(value);}
    public String fingerprint(JsonNode value) {
        try{return digest(mapper.writeValueAsBytes(value));}catch(Exception e){throw new StorageFailure();}
    }
    public <T> T value(JsonNode node,Class<T> type) {
        try {return mapper.treeToValue(node,type);}catch(Exception e){throw new StorageFailure();}
    }

    public synchronized void write(String id,JsonNode payload) {
        Path temporary=null;
        try {
            ObjectNode envelope=mapper.createObjectNode();envelope.put("version",1);envelope.put("id",id);
            envelope.set("payload",payload);envelope.put("sha256",digest(mapper.writeValueAsBytes(payload)));
            byte[] bytes=mapper.writeValueAsBytes(envelope);
            if(bytes.length>MAX_BYTES || Files.isSymbolicLink(file(id)))throw new StorageFailure();
            temporary=Files.createTempFile(directory,"pending-",".tmp");
            try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            Files.move(temporary,file(id),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } catch(Exception e) {throw new StorageFailure();}
        finally {
            if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(Exception ignored){}
        }
    }

    public synchronized JsonNode read(String id) {
        if(!Files.exists(file(id)))return null;
        return checked(file(id),id);
    }
    private JsonNode checked(Path path,String expectedId) {
        try {
            if(Files.isSymbolicLink(path) || Files.size(path)>MAX_BYTES)throw new StorageFailure();
            JsonNode envelope=mapper.readTree(Files.readAllBytes(path));
            String id=envelope.path("id").asText();JsonNode payload=envelope.path("payload");
            if(envelope.path("version").asInt()!=1 || !id.equals(expectedId)
                    || !path.getFileName().equals(file(id).getFileName()) || payload.isMissingNode()
                    || !digest(mapper.writeValueAsBytes(payload)).equals(envelope.path("sha256").asText()))throw new StorageFailure();
            return payload;
        }catch(Exception e){throw new StorageFailure();}
    }

    public synchronized Map<String,JsonNode> all() {
        Map<String,JsonNode> result=new LinkedHashMap<>();
        try(var paths=Files.list(directory)) {
            for(Path path:paths.filter(p->p.getFileName().toString().endsWith(".json")).limit(10_001).toList()) {
                if(result.size()>=10_000 || Files.isSymbolicLink(path) || Files.size(path)>MAX_BYTES)throw new StorageFailure();
                String id=mapper.readTree(Files.readAllBytes(path)).path("id").asText();
                result.put(id,checked(path,id));
            }
        }catch(Exception e){throw new StorageFailure();}
        return result;
    }
    public synchronized void delete(String id) {
        try {Files.deleteIfExists(file(id));}catch(Exception e){throw new StorageFailure();}
    }
}
