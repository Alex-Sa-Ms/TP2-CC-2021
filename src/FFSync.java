import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class FFSync {
    /*private class PacketResender extends TimerTask {
        private final DatagramSocket ds;
        private final Map<String,Long> files;
        private final Map<Short,AbstractMap.SimpleEntry<DatagramSocket,PackageInfo>> packets;

        PacketResender(DatagramSocket ds, Map<String,Long> files, Map<Short,AbstractMap.SimpleEntry<DatagramSocket,PackageInfo>> packets){
            this.ds      = ds;
            this.files   = files;
            this.packets = packets;
        }

        @Override
        public void run() {

        }
    }*/
    public static final int MAXTHREADSNUMBERPERFUNCTION = 30; //if MAXTHREADSNUMBERPERFUNCTION = 10, then 10 threads can send files, and another 10 threads can receive files
    public static int CURRENTRECEIVERSNUMBER = 0; //Number of receivers running
    public static int CURRENTSENDERSNUMBER   = 0; //Number of senders running

    public static void main(String[] args) {
        String folderPath = args[0]; //Tem de acabar com a barra "/" no Linux ou com a barra "\" se for no Windows
        String externalIP = args[1];
        ReentrantLock readLock = new ReentrantLock();
        ReentrantLock writeLock = new ReentrantLock();
        Map<String,TransferWorker> requestsSent = new HashMap<>();
        Map<String,TransferWorker> requestsReceived = new HashMap<>();
        Map<String,Long> filesInDir;
        DatagramSocket ds;

        //Connection verifications
        if (!testConnection(externalIP)) {
            System.out.println("Couldn't find the other client!");
            return;
        }

        try {
            ds = new DatagramSocket(9999);
            ds.connect(InetAddress.getByName(externalIP),9999);
            ds.setSoTimeout(10000); //10 seg de timeout
        }
        catch (SocketException e){
            System.out.println("Error opening connection socket");
            return;
        }
        catch (Exception e) {
            System.out.println(e.getMessage());
            return;
        }

        ///Authentication Block
        FTrapid ftr = new FTrapid(ds);
        Scanner sc = new Scanner(System.in);
        System.out.print("Introduza a sua password, em ambas as maquinas: ");
        String pass = sc.next();
        try {
           if (ftr.authentication(pass)!=1) {
               System.out.println("Palavra passe errada");
               return;
           }
        } catch (Exception e) { //isto vai apanhar tanto a IOException como a exceção por limite
            e.printStackTrace();
            System.out.println("Erro na autenticação: " + e.getMessage());
            return;
        }
        System.out.println("Sucesso na autenticação!!");

        try {
            //Fills the map with the files present in the given directory
            if((filesInDir = fillDirMap(folderPath)) == null) {
                System.out.println("Not a directory!");
                return;
            }

            Map<String, Long> filesInDirReceived;

            //Decides the order in which it will receive the map of files from the other client
            if(ds.getLocalAddress().getHostAddress().compareTo(externalIP) < 0) {
                ftr.sendData(serialize((HashMap<String, Long>) filesInDir));
                filesInDirReceived = deserialize(ftr.receiveData());
            }
            else{
                filesInDirReceived = deserialize(ftr.receiveData());
                ftr.sendData(serialize((HashMap<String, Long>) filesInDir));
            }

            //Corrects the map of files that need to be sent
            analyse(filesInDir,filesInDirReceived);
        }catch (IOException | ClassNotFoundException e) {
            System.out.println("Error sending/receiving list of files!");
            return;
        }


        ConnectionWorker receiver = new ConnectionWorker(true, externalIP, folderPath, filesInDir, ds, ftr, readLock, writeLock, requestsSent, requestsReceived);
        ConnectionWorker sender   = new ConnectionWorker(false, externalIP, folderPath, filesInDir, ds, ftr, readLock, writeLock, requestsSent, requestsReceived);

        receiver.start();
        sender.start();

        try {
            receiver.join();
            sender.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /* ******** Auxiliar Methods ******** */


    public static Map<String,Long> fillDirMap(String path){
        Map<String,Long> filesInDir = new HashMap<>();
        File dir = new File(path);
        System.out.println(File.separator);
        if (dir.isDirectory()){
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.isFile()) {
                        long data = f.lastModified();
                        String name = f.getName();
                        filesInDir.put(name, data);
                    }
                    else if (f.isDirectory()){
                        Map<String,Long> filesInDir2 = fillDirMap(f.getPath());
                        filesInDir2.forEach((k,v)-> filesInDir.put(f.getName()+"/"+k,v)); //so esta a funcionar para windows
                    }
                }
            }
        }
        else return null;

        return filesInDir;
    }

    public static String[] pathToArray(String path){
        return path.split("/");
    }

    public static boolean testConnection(String externalIP){
        try{
            InetAddress s = InetAddress.getByName(externalIP);
            // System.out.println("Ligado!!!");
            return true;
        }
        catch (IOException e) {/*System.out.println("Falhei!!!"); */return false;}
    }

    public static byte[] serialize(HashMap<String,Long> filesInDir) throws IOException {
        byte[] bytes;
        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(byteOut);

        out.writeObject(filesInDir);

        bytes = byteOut.toByteArray();
        out.close();
        byteOut.close();

        return bytes;
    }

    public static Map<String,Long> deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
        Map<String,Long> map;
        ByteArrayInputStream byteIn = new ByteArrayInputStream(bytes);
        ObjectInputStream in = new ObjectInputStream(byteIn);

        map = (HashMap<String,Long>) in.readObject();

        byteIn.close();
        in.close();

        return map;
    }

    //Receives 2 maps, one containing all the files present in the machine's given folder, and another containing all the files the other machine's can share
    //Removes all the files from the first map, that match the name of a file from the other machine, but are not as recent
    public static void analyse(Map<String,Long> filesInDir, Map<String,Long> filesInDirReceived) {
        String filename;

        for(Map.Entry<String,Long> entry : filesInDirReceived.entrySet()) {
            filename = entry.getKey();
            //Checks for the existence of the file. If the file exists, compares the dates when they were last modified.
            if (filesInDir.containsKey(filename) && filesInDir.get(filename) < entry.getValue())
                filesInDir.remove(filename);
        }
    }

    /* ******** Test Methods ******** */

    public static void generateFile(String filepath, int length){
        File file = new File(filepath);
        try {
            file.createNewFile();
        } catch (IOException e) {
            System.out.println("Couldnt create file");
            return;
        }
        FileOutputStream fops = null;

        try {
            fops = new FileOutputStream(file);
        } catch (FileNotFoundException e) {
            System.out.println("Error creating/opening file: " + filepath);
            return;
        }
        try {
            byte[] buffer = new byte[length];
            Random rand = new Random();
            rand.nextBytes(buffer);
            fops.write(buffer);
            fops.flush();
        } catch (IOException e) {
            System.out.println("Error writing file : " + filepath);
            return;
        }
    }
}
