package node;

import common.Hasher;
import common.JsonUtil;
import io.grpc.stub.StreamObserver;
import net.grpc.chord.*;

import java.util.*;
import java.util.logging.Logger;

class ChordNodeService extends ChordNodeServiceGrpc.ChordNodeServiceImplBase {

//        private static final String TAG = ChordNodeService.class.getName();
    private static final Logger logger = Logger.getLogger(ChordNodeService.class.getName());

    private HashMap<String, String> hashMap;
    private HashMap<Integer, HashMap<String, String>> replica;
    private int selfID;
    private static int ringSizeExp = 5;
    private static int sucListSize = 3;
    private String selfIP;
    private int selfPort;
    private Identifier[] fingerTable;
    private Identifier[] successorsList;
    private Identifier predecessor;
    private int next;
    private Hasher hasher;

    public Identifier getFingerTableEntry(int index) {
        return this.fingerTable[index];
    }

    public void setFingerTableEntry(int index, Identifier identifier) {
        this.fingerTable[index] = identifier;
    }

    public Identifier getSuccessorsListEntry(int index) {
        return successorsList[index];
    }

    public void setSuccessorListEntry(int index, Identifier identifier) {
        this.successorsList[index] = identifier;
    }

    public Identifier getPredecessor() {
        return predecessor;
    }

    public void setPredecessor(Identifier predecessor) {
        this.predecessor = predecessor;
    }

    public ChordNodeService(int selfID, String selfIP, int selfPort){
        hashMap = new HashMap<String, String>();
        replica = new HashMap<Integer, HashMap<String, String>>();
        this.fingerTable = new Identifier[ringSizeExp];
        this.successorsList = new Identifier[sucListSize];
        this.selfID = selfID;
        this.selfIP = selfIP;
        this.selfPort = selfPort;
        hasher = new Hasher(1 << ringSizeExp);
    }

    @Override
    public void notify(NotifyRequest request, StreamObserver<NotifyResponse> responseObserver) {
        int senderID = request.getIdentifier().getID();
        String address = request.getIdentifier().getIP();
        int port = request.getIdentifier().getPort();
        if (predecessor == null || inRange(senderID, predecessor.getID(), selfID)) {
            if (predecessor == null) predecessor = Identifier.newBuilder().build();
            predecessor = predecessor.toBuilder().setID(senderID).setIP(address).setPort(port).build();
            ChordNodeClient predecessorClient = new ChordNodeClient(predecessor.getIP(), predecessor.getPort());
            String dataJson = generateDataJsonAndDeleteLocal(predecessor.getID());
            predecessorClient.acceptMyData(dataJson);
            predecessorClient.close();
        }
        NotifyResponse response = NotifyResponse.newBuilder().build();


        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }


    @Override
    public void findSuccessor(FindSuccessorRequest request, StreamObserver<FindSuccessorResponse> responseObserver) {
        Identifier successor = this.getAliveSuccessor();

        if (predecessor != null && this.inRange(request.getID(), predecessor.getID(), selfID)) {
            FindSuccessorResponse response = FindSuccessorResponse.newBuilder().setIdentifier(generateSelfIdentifier()).build();
            responseObserver.onNext(response);
        }
        else if (successor != null && (this.inRange(request.getID(), selfID, successor.getID())))
        {
            FindSuccessorResponse response = FindSuccessorResponse.newBuilder().setIdentifier(successor).build();
            responseObserver.onNext(response);
        } else{
            int searchedID = request.getID();
            Identifier nextIdentifier = closestPrecedingFinger(searchedID);

            ChordNodeClient client = new ChordNodeClient(nextIdentifier.getIP(), nextIdentifier.getPort());
            Identifier searchedIdentifier = client.findSuccessor(searchedID);
            client.close();

            FindSuccessorResponse response = FindSuccessorResponse.newBuilder().setIdentifier(searchedIdentifier).build();
            responseObserver.onNext(response);
        }
        responseObserver.onCompleted();
    }

    private void create(){
        predecessor = null;
        this.fingerTable[0] = Identifier.newBuilder().setID(selfID).setIP(selfIP).setPort(selfPort).build();

        maintainFirstReplica(this.successorsList[0], this.fingerTable[0]);
        this.successorsList[0] = this.fingerTable[0];

        for (int i = 1;i < ringSizeExp;i++) this.fingerTable[i] = Identifier.newBuilder().setID(-1).build();
        for (int i = 1;i < sucListSize;i++) this.successorsList[i] = Identifier.newBuilder().setID(-1).build();
    }

    private void join(Identifier knownNodeIdentifier){
        predecessor = null;
        logger.info("Creating client for join");
        ChordNodeClient knownNodeClient = new ChordNodeClient(knownNodeIdentifier.getIP(), knownNodeIdentifier.getPort());

        this.fingerTable[0] = knownNodeClient.findSuccessor(selfID);

        maintainFirstReplica(this.successorsList[0], this.fingerTable[0]);
        this.successorsList[0] = this.fingerTable[0];

        knownNodeClient.close();
    }

    private void stabilize() {
        Identifier successor = this.getAliveSuccessor();

        if (successor.getID() != this.fingerTable[0].getID()) {
            this.fingerTable[0] = successor;
            this.successorsList[0] = successor;
        }

        if (successor != null) {
            logger.info("Creating client for stabilize on successor");
            ChordNodeClient successorClient = new ChordNodeClient(successor.getIP(), successor.getPort());
            Identifier successorPredecessor = successorClient.inquirePredecessor();

            if (!successorPredecessor.getIP().equals("") && inRange(successorPredecessor.getID(), selfID, successor.getID())) {
                successorClient.close();

                successorClient = new ChordNodeClient(successorPredecessor.getIP(), successorPredecessor.getPort());
                if (successorClient.ping()) {
                    this.fingerTable[0] = successorPredecessor;

                    maintainFirstReplica(this.successorsList[0], this.fingerTable[0]);
                    this.successorsList[0] = this.fingerTable[0];
                } else  {
                    successorClient = new ChordNodeClient(successor.getIP(), successor.getPort());
                }
            }
            successorClient.notify(this.generateSelfIdentifier());

            successorClient.close();
        }

        updateSuccessorsList();

        printKeyValue();
        printReplica();
    }

    private Identifier getAliveSuccessor() {
        for (int i = 0;i < sucListSize;i++) {
            Identifier curSuccessor = this.successorsList[i];
            ChordNodeClient client = new ChordNodeClient(curSuccessor.getIP(), curSuccessor.getPort());
            if (client.ping()) {
                client.close();

                return curSuccessor;
            }

            client.close();
        }

        return generateSelfIdentifier();
    }

    private void checkPredecessor(){
        if (this.predecessor != null) {
            logger.info("Creating client for checkPredecessor");
            ChordNodeClient client = new ChordNodeClient(this.predecessor.getIP(), this.predecessor.getPort());
            if (!client.ping()) {
                this.predecessor = null;
            }

            client.close();
        }
    }

    private boolean inRange(int id, int leftID, int rightID) {
        if (leftID < rightID) {
            return id > leftID && id <= rightID;
        } else {
            return id > leftID || id <= rightID;
        }
    }

    private void updateSuccessorsList() {
        Identifier successor = this.getAliveSuccessor();
        ChordNodeClient successorClient = new ChordNodeClient(successor.getIP(), successor.getPort());
        List<Identifier> successorsList = new ArrayList<>(successorClient.inquireSuccessorsList());
        successorClient.close();



        Identifier[] oldSuccessorList = Arrays.copyOf(this.successorsList, this.successorsList.length);

        successorsList.remove(successorsList.size() - 1);
        successorsList.add(0, successor);

        // deduplicate
        HashSet<Identifier> set = new HashSet<>();
        for (int i = 0; i < successorsList.size(); i++) {
            // encounter -1 indicating no valid node after this point
            if (successorsList.get(i).getID() == -1) {
                break;
            }
            // add the current identifier to the hashset for deduplication
            if (set.add(successorsList.get(i))) {
                continue;
            }
            // if adding failed, duplicate node occurs, set the identifier to -1 (null)
            else {
                successorsList.set(i, Identifier.newBuilder().setID(-1).build());
            }
        }

        successorsList.toArray(this.successorsList);

        maintainSubsequentReplicas(oldSuccessorList, this.successorsList);

        printSuccessorList();
    }

    @Override
    public void inquireSuccessorsList(InquireSuccessorsListRequest request, StreamObserver<InquireSuccessorsListResponse> responseObserver) {
        List<Identifier> sucList = Arrays.asList(this.successorsList);
        InquireSuccessorsListResponse response = InquireSuccessorsListResponse.newBuilder().addAllSuccessorsList(sucList).build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void put(PutRequest request, StreamObserver<PutResponse> responseObserver) {
        String key = request.getKey();
        String value = request.getValue();
        PutResponse response;

        if (!inRange(hasher.hash(key), predecessor.getID(), selfID)) {
            response = PutResponse.newBuilder().setRet(ReturnCode.FAILURE).build();
        } else {
            hashMap.put(key, value);
            response = PutResponse.newBuilder().setRet(ReturnCode.SUCCESS).build();

//                put to all successors
            for(Identifier identifier : successorsList){
                ChordNodeClient successorClient = new ChordNodeClient(identifier.getIP(), identifier.getPort());
                successorClient.addScatteredReplica(generateSelfIdentifier(), key, value);
                successorClient.close();
            }
        }


        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void get(GetRequest request, StreamObserver<GetResponse> responseObserver) {
        String key = request.getKey();

        if (!hashMap.containsKey(key)) {
            GetResponse response = GetResponse.newBuilder().setRet(ReturnCode.FAILURE).build();
            responseObserver.onNext(response);
        }
        else {
            String value = hashMap.get(key);
            GetResponse response = GetResponse.newBuilder().setValue(value).setRet(ReturnCode.SUCCESS).build();
            responseObserver.onNext(response);
        }
        responseObserver.onCompleted();
    }



    @Override
    public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
        PingResponse response = PingResponse.newBuilder().build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void inquirePredecessor(InquirePredecessorRequest request, StreamObserver<InquirePredecessorResponse> responseObserver) {
        InquirePredecessorResponse response;
        if(predecessor == null){
            response = InquirePredecessorResponse.newBuilder().build();
        }else{
            response = InquirePredecessorResponse.newBuilder().setIdentifier(predecessor).build();
        }
        responseObserver.onNext(response);
        responseObserver.onCompleted();

    }

    @Override
    public void transferData(TransferDataRequest request, StreamObserver<TransferDataResponse> responseObserver) {
        int requestID = request.getID();
        HashMap<String, String> hashMapToTransfer = generateTransferredMap(requestID);

        String dataJson = JsonUtil.serilizable(hashMapToTransfer);
        TransferDataResponse response = TransferDataResponse.newBuilder().setDataJson(dataJson).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void acceptMyData(AcceptMyDataRequest request, StreamObserver<AcceptMyDataResponse> responseObserver) {
        String dataJson = request.getDataJson();
        HashMap<String, String> gotHashMap = JsonUtil.deserilizable(dataJson);
        for (Map.Entry<String, String> entry : gotHashMap.entrySet()) {
            hashMap.put(entry.getKey(), entry.getValue());
        }
        AcceptMyDataResponse response = AcceptMyDataResponse.newBuilder().build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void removeReplica(RemoveReplicaRequest request, StreamObserver<RemoveReplicaResponse> responseObserver) {
        int replicaTagID = request.getIdentifier().getID();

        this.replica.remove(replicaTagID);
        RemoveReplicaResponse response = RemoveReplicaResponse.newBuilder().build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void addReplica(AddReplicaRequest request, StreamObserver<AddReplicaResponse> responseObserver) {
        int requestTagID = request.getIdentifier().getID();
        String dataJson = request.getJsonData();
        HashMap<String, String> hashMapToAdd = JsonUtil.deserilizable(dataJson);
        this.replica.put(requestTagID, hashMapToAdd);

        AddReplicaResponse response = AddReplicaResponse.newBuilder().build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void addScatteredReplica(AddScatteredReplicaRequest request, StreamObserver<AddScatteredReplicaResponse> responseObserver){
        String key = request.getKey();
        String value = request.getValue();
        int requestTagID = request.getIdentifier().getID();
        replica.get(requestTagID).put(key, value);

        AddScatteredReplicaResponse response = AddScatteredReplicaResponse.newBuilder().build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }



    private void start(int id, String ip, int port){
        create();
        if (id != -1) {
            Identifier identifier = Identifier.newBuilder().setID(id).setIP(ip).setPort(port).build();
            join(identifier);
        }

        this.next = 0;
        Timer timer = new Timer();

        StabilizeTask stabilizeTask = new ChordNodeService.StabilizeTask();
        timer.schedule(stabilizeTask, 1000, 1000);

        ChordNodeService.CheckPredecessorTask checkPredecessorTask = new ChordNodeService.CheckPredecessorTask();
        timer.schedule(checkPredecessorTask, 1000, 1000);

        ChordNodeService.FixFingersTask fixFingersTask = new ChordNodeService.FixFingersTask();
        timer.schedule(fixFingersTask, 1000, 500);
    }

    private void printFingerTable() {
        logger.info("||index || value");

        StringBuilder sb = new StringBuilder();
        for (int i = 0;i < ringSizeExp;i++) {
            sb.append(String.format("||   %d   || %d\n", i, fingerTable[i].getID()));
        }
        System.out.println(sb.toString());
    }

    private void printKeyValue() {
        logger.info("||key || value");

        StringBuilder sb = new StringBuilder();
        for (String key : hashMap.keySet()) {
            sb.append(String.format("||%s  || %s\n", key, hashMap.get(key)));
        }
        System.out.println(sb.toString());
    }

    private void printReplica() {
        logger.info("||ID || value");

        System.out.println(this.replica);
    }

    private void printSuccessorList() {
        logger.info("||index || value");

        StringBuilder sb = new StringBuilder();
        for (int i = 0;i < sucListSize;i++) {
            sb.append(String.format("||   %d   || %d\n", i, successorsList[i].getID()));
        }
        System.out.println(sb.toString());
    }


    private Identifier closestPrecedingFinger(int id) {
        for (int i = ringSizeExp - 1;i >= 0;i--) {
            if (fingerTable[i].getID() == -1) {
                continue;
            }

            if (inRange(fingerTable[i].getID(), selfID, id)) {
                return fingerTable[i];
            }
        }

        return generateSelfIdentifier();
    }

    private void fixFingers() {
        this.next = (this.next + 1) % ringSizeExp;

        ChordNodeClient selfClient = new ChordNodeClient(selfIP, selfPort);
        Identifier searchedIdentifier = selfClient.findSuccessor((selfID + (1 << this.next)) % (1 << ringSizeExp));

        if (searchedIdentifier == null) {
            searchedIdentifier = Identifier.newBuilder().setID(-1).build();
        }

        selfClient.close();

        this.fingerTable[this.next] = searchedIdentifier;

        printFingerTable();
    }


    class StabilizeTask extends TimerTask {
        public void run() {
            stabilize();
            logger.info(String.format("Successor : %d", getAliveSuccessor().getID()));
        }
    }

    class CheckPredecessorTask extends TimerTask {
        public void run() {
            checkPredecessor();
            logger.info(String.format("Predecessor : %d", predecessor == null ? -1 : predecessor.getID()));
        }
    }

    class FixFingersTask extends TimerTask {
        public void run() {
            fixFingers();
            logger.info(String.format("Predecessor : %d", predecessor == null ? -1 : predecessor.getID()));
        }
    }

    public void maintainFirstReplica(Identifier oldSuccessor, Identifier newSuccessor) {
        if (oldSuccessor != null && oldSuccessor.getID() != -1 && oldSuccessor.getID() != selfID) {
            ChordNodeClient oldSuccessorClient = new ChordNodeClient(oldSuccessor.getIP(), oldSuccessor.getPort());
            oldSuccessorClient.removeReplica(generateSelfIdentifier());
            oldSuccessorClient.close();
        }

        if (newSuccessor != null && newSuccessor.getID() != -1 && newSuccessor.getID() != selfID) {
            ChordNodeClient newSuccessorClient = new ChordNodeClient(newSuccessor.getIP(), newSuccessor.getPort());
            String dataJson = JsonUtil.serilizable(hashMap);
            newSuccessorClient.addReplica(generateSelfIdentifier(), dataJson);
            newSuccessorClient.close();
        }
    }


    //        DEBUG successor 0 hasnt been replicated
    private void maintainSubsequentReplicas(Identifier[] oldList, Identifier[] newList){
        HashSet<Identifier> oldSet = new HashSet<>(Arrays.asList(oldList));
        HashSet<Identifier> newSet = new HashSet<>(Arrays.asList(newList));

        System.out.println(oldSet);
        System.out.println(newSet);

        HashSet<Identifier> tmp = new HashSet<>(oldSet);
        oldSet.removeAll(newSet);
        newSet.removeAll(tmp);


        for(Identifier identifier : oldSet){
            if(identifier.getID() == -1 || identifier.getID() == selfID)continue;
            ChordNodeClient oldSuccessorClient = new ChordNodeClient(identifier.getIP(), identifier.getPort());
            oldSuccessorClient.removeReplica(generateSelfIdentifier());
            oldSuccessorClient.close();
        }

        for (Identifier identifier : newSet) {
            if(identifier.getID() == -1 || identifier.getID() == selfID)continue;
            ChordNodeClient newSuccessorClient = new ChordNodeClient(identifier.getIP(), identifier.getPort());
            String dataJson = JsonUtil.serilizable(hashMap);
            newSuccessorClient.addReplica(generateSelfIdentifier(), dataJson);
            newSuccessorClient.close();
        }
    }

    private Identifier generateSelfIdentifier(){
        return Identifier.newBuilder().setID(selfID).setIP(selfIP)
                .setPort(selfPort).build();
    }

    private HashMap<String, String> generateTransferredMap(int id) {
        HashMap<String, String> hashMapToTransfer = new HashMap<>();
//            prepare keys to transfer
        for (Map.Entry<String, String> entry : hashMap.entrySet()) {
            if(hasher.hash(entry.getKey()) <= id){
                hashMapToTransfer.put(entry.getKey(), entry.getValue());
            }
        }

        return hashMapToTransfer;
    }

    private String generateDataJsonAndDeleteLocal(int predecessorID){
        HashMap<String, String> hashMapToTransfer = generateTransferredMap(predecessorID);

//            remove local keys
        for (Map.Entry<String, String> entry : hashMapToTransfer.entrySet()) {
            if(hasher.hash(entry.getKey()) <= predecessorID){
                hashMap.remove(entry.getKey());
            }
        }

        return JsonUtil.serilizable(hashMapToTransfer);
    }
}