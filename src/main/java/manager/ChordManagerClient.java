package manager;

import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import net.grpc.chord.*;

import java.util.logging.Level;
import java.util.logging.Logger;

public class ChordManagerClient {
    private static final Logger logger = Logger.getLogger(ChordManagerClient.class.getName());

    private final ManagedChannel channel;
    private final ChordManagerServiceGrpc.ChordManagerServiceBlockingStub blockingStub;
    private final ChordManagerServiceGrpc.ChordManagerServiceStub asyncStub;
    private final ChordManagerServiceGrpc.ChordManagerServiceFutureStub futureStub;

    public ChordManagerClient(String host, int port) {
        this(ManagedChannelBuilder.forAddress(host, port).usePlaintext());
    }

    public ChordManagerClient(ManagedChannelBuilder<?> channelBuilder) {
        channel = channelBuilder.build();
        blockingStub = ChordManagerServiceGrpc.newBlockingStub(channel);
        asyncStub = ChordManagerServiceGrpc.newStub(channel);
        futureStub = ChordManagerServiceGrpc.newFutureStub(channel);
    }

    public Identifier findSuccessor(int id) {
        FindRequest findRequest = FindRequest.newBuilder().setID(id).build();
        FindResponse findResponse;
        try {
            findResponse = blockingStub.findSuccessor(findRequest);
        } catch (StatusRuntimeException e){
            logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
            return null;
        }
        int successorID = findResponse.getID();
        String successorIP = findResponse.getAddress();
        int successorPort = findResponse.getPort();
        Identifier successorIdentifier = Identifier.newBuilder()
                .setID(successorID).setIP(successorIP).setPort(successorPort).build();

        return successorIdentifier;
    }
    public Identifier join(int ID, String IP, int port) {
        JoinRequest joinRequest = JoinRequest.newBuilder().setID(ID)
                .setAddress(IP).setPort(port).build();
        JoinResponse joinResponse;

        try {
            joinResponse = blockingStub.join(joinRequest);
        } catch (StatusRuntimeException e){
            logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
            return null;
        }

        int successorID = joinResponse.getID();
        String successorIP = joinResponse.getAddress();
        int successorPort = joinResponse.getPort();


        Identifier successorIdentifier = Identifier.newBuilder()
                .setID(successorID).setIP(successorIP).setPort(successorPort).build();

        return successorIdentifier;

    }

    public void close() {
        this.channel.shutdownNow();
    }

    public boolean put(String key, String val){
        PutRequest request = PutRequest.newBuilder().setKey(key).setValue(val).build();
        PutResponse putResponse;
        try {
            putResponse = blockingStub.putManager(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.WARNING, "RPC from manager failed: {0}", e.getStatus());
            return false;
        }

        return putResponse.getRet() == ReturnCode.SUCCESS;
    }

    public String get(String key){
        GetRequest request = GetRequest.newBuilder().setKey(key).build();
        GetResponse getResponse;
        try {
            getResponse = blockingStub.getManager(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.WARNING, "RPC from manager failed: {0}", e.getStatus());
            return null;
        }

        return getResponse.getRet() == ReturnCode.SUCCESS ? getResponse.getValue() : null;
    }

    public ListenableFuture<PutResponse> putFuture(String key, String val){
        PutRequest request = PutRequest.newBuilder().setKey(key).setValue(val).build();
        ListenableFuture<PutResponse> putResponseFuture;
        try {
            putResponseFuture = futureStub.putManager(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.WARNING, "RPC from manager failed: {0}", e.getStatus());
            return null;
        }

        return putResponseFuture;
    }


    public ListenableFuture<FindResponse> findSuccessorFuture(int id) {
        FindRequest findRequest = FindRequest.newBuilder().setID(id).build();
        ListenableFuture<FindResponse> findResponseFuture;
        try {
            findResponseFuture = futureStub.findSuccessor(findRequest);
        } catch (StatusRuntimeException e){
            logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
            return null;
        }

        return findResponseFuture;
    }

    public String ls(String dirPath) {
        lsRequest request = lsRequest.newBuilder().setDir(dirPath).build();
        lsResponse response;
        try {
            response = blockingStub.ls(request);
        } catch (StatusRuntimeException e){
            logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
            return "";
        }

        return response.getDirs() + "dirFileDivider" + response.getFiles();
    }


    public boolean writeFileKey(String absPath, String fileKey) {
        WriteFileKeyRequest request = WriteFileKeyRequest.newBuilder().setAbsPath(absPath).setFileKey(fileKey).build();
        WriteFileKeyResponse response;
        try {
            response = blockingStub.writeFileKey(request);
        } catch (StatusRuntimeException e){
            logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
            return false;
        }
        return true;
    }

    public String readFileKey(String absPath) {
        ReadFileKeyRequest request = ReadFileKeyRequest.newBuilder().setAbsPath(absPath).build();
        ReadFileKeyResponse response;
        try {
            response = blockingStub.readFileKey(request);
        } catch (StatusRuntimeException e){
            logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
            return "";
        }

        return response.getFileKey();
    }




}