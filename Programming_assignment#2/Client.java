import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Client {
    static Path path = Paths.get("");
    static String present_path = path.toAbsolutePath().toString();
    static byte SeqNo;
    static short CHKsum = 0x0000;
    static short Size;
    static int sendbase;
    static int win_size = 5;
    static int drop = -1;
    static int timeout = -1;
    static int biterror = -1;

    public static synchronized void set_sendbase(byte[] ackCHK, byte tmp) {
        int j;

        ackCHK[tmp] = 1;
        for (j = 0; j < 16; j++) {
            if (ackCHK[j] == 0) {
                sendbase = j;
                break;
            }
        }
    }

    public static void main(String[] args) throws Exception {
        String requestmessage;
        String responsemessage;
        String[] answer;
        String[] status;
        String[] filelist;
        int i;
        String ftp_server_host = "127.0.0.1";
        int control_port = 2020;
        int data_port = 2121;
        if (args.length > 0) {
            ftp_server_host = args[0];
            control_port = Integer.parseInt(args[1]);
            data_port = Integer.parseInt(args[2]);
        }

        Socket clientSocket = new Socket(ftp_server_host, control_port);

        DataOutputStream outToServer = new DataOutputStream(clientSocket.getOutputStream());

        BufferedReader inFromServer = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

        while (true) {
            System.out.print("Input Command :");
            BufferedReader inFromUser = new BufferedReader(new InputStreamReader(System.in));

            requestmessage = inFromUser.readLine();

            answer = requestmessage.split(" ");

            outToServer.writeBytes(requestmessage + '\n');

            // 'LIST' command
            if (answer[0].equals("LIST")) {
                responsemessage = inFromServer.readLine();

                status = responsemessage.split(" ");
                if (status[0].equals("200")) {
                    responsemessage = inFromServer.readLine();
                    filelist = responsemessage.split(",");
                    for (i = 0; i < filelist.length; i += 2) {
                        System.out.println(filelist[i] + "," + filelist[i + 1]);
                    }
                } else if (status[0].equals("300")) {
                    System.out.println("Failed - Not Exist");
                } else if (status[0].equals("400")) {
                    System.out.println("Failed - Not directory");
                }
            }

            // 'CD' command
            else if (answer[0].equals("CD")) {
                responsemessage = inFromServer.readLine();
                System.out.println(responsemessage);

                status = responsemessage.split(" ");
                if (status[0].equals("200")) {
                    System.out.println(status[3]);
                } else if (status[0].equals("300")) {
                    System.out.println("Failed - Not Exist");
                } else if (status[0].equals("400")) {
                    System.out.println("Failed - Not directory");
                }
            }

            // 'GET' command
            else if (answer[0].equals("GET")) {
                Socket dataSocket = new Socket(ftp_server_host, data_port);
                responsemessage = inFromServer.readLine();
                // System.out.println(responsemessage);
                InputStream datainFromServer = dataSocket.getInputStream();
                OutputStream acksignal = dataSocket.getOutputStream();
                byte[] data = new byte[1005];

                status = responsemessage.split(" ");
                if (status[0].equals("200")) {
                    Path path = Paths.get(answer[1]);
                    System.out.println("Received " + path.getFileName() + "/ " + status[2] + "bytes");
                    FileOutputStream fos = new FileOutputStream(present_path + "/" + path.getFileName().toString());
                    int n;
                    while ((n = datainFromServer.read(data)) != -1) {
                        SeqNo = data[0];
                        CHKsum = (short) ((data[1] << 8) | data[2] & 0xFF);
                        Size = (short) ((data[3] << 8) | data[4] & 0xFF);
                        if (n == 1005) {
                            fos.write(data, 5, 1000);
                        } else if (n < 1005) {
                            fos.write(data, 5, n - 5);
                        }
                        acksignal.write(data, 0, 3);
                        System.out.print("# ");
                    }
                    System.out.println("Completed!!");
                    fos.close();
                } else if (status[0].equals("300")) {
                    System.out.println("Failed - Not Exist");
                } else if (status[0].equals("400")) {
                    System.out.println("Failed - Not file");
                }
                dataSocket.close();
            }

            // 'PUT' command
            else if (answer[0].equals("PUT")) {
                Socket dataSocket = new Socket(ftp_server_host, data_port);
                OutputStream dataoutToServer = dataSocket.getOutputStream();
                InputStream acksignal = dataSocket.getInputStream();
                byte[] data = new byte[1005];
                byte[] ackCHK = new byte[16];
                byte[][] ready_retrans = new byte[16][1005];
                Thread[] datatrasfer = new Thread[win_size];
                Thread[] timer = new Thread[16];
                int print_var = -1;

                for(i = 0; i < 16; i++){
                    ackCHK[i] = 0;
                }

                File file = new File(present_path + "/" + answer[1]);
                Size = (short) file.length();
                SeqNo = 0;

                if (!file.exists()) {
                    System.out.println("Failed - Not Exist");
                    requestmessage = Integer.toString(0);
                    outToServer.writeBytes(requestmessage + '\n');
                    dataSocket.close();
                    continue;
                }
                if (file.isDirectory()) {
                    System.out.println("Failed - Not file");
                    requestmessage = Integer.toString(0);
                    outToServer.writeBytes(requestmessage + '\n');
                    dataSocket.close();
                    continue;
                }

                requestmessage = Long.toString(file.length());
                outToServer.writeBytes(requestmessage + '\n');

                responsemessage = inFromServer.readLine();

                status = responsemessage.split(" ");
                if (status[0].equals("500")) {
                    System.out.println("Failed for unknown reason");
                    dataSocket.close();
                } else {
                    System.out.println(answer[1] + " transferred " + file.length() + " bytes");

                    FileInputStream fis = new FileInputStream(present_path + "/" + answer[1]);
                    sendbase = 0;
                    int n;

                    class window_shifting implements Runnable {
                        byte tmp;
                        byte final_seqnum;
                        byte k;
                        int sum = 0;

                        window_shifting(byte finalnum) {
                            final_seqnum = finalnum;
                        }

                        @Override
                        public void run() {
                            try {
                                while (true) {
                                    for(k = 0; k < final_seqnum; k++){
                                        sum = sum + ackCHK[k];
                                    }
                                    if(sum == final_seqnum){
                                        break;
                                    }
                                    sum = 0;
                                    acksignal.read(data, 0, 3);
                                    tmp = data[0];
                                    set_sendbase(ackCHK, tmp);
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    int lastlength = 0;
                    while ((n = fis.read(data, 5, 1000)) != -1) {
                        data[0] = SeqNo;
                        data[1] = (byte) ((CHKsum >> 8) & 0xFF);
                        data[2] = (byte) (CHKsum & 0xFF);
                        data[3] = (byte) ((Size >> 8) & 0xFF);
                        data[4] = (byte) (Size & 0xFF);
                        ready_retrans[SeqNo] = data.clone();
                        SeqNo++;
                        lastlength = n;
                        CHKsum = 0x0000;
                    }

                    class retrans_timer implements Runnable {
                        int a;
                        int finalseqnum;
                        int lastlength;

                        retrans_timer(int i, int finalseq, int last) {
                            a = i;
                            finalseqnum = finalseq;
                            lastlength = last;
                        }

                        @Override
                        public void run() {
                            try {
                                Thread.sleep(1000);
                                while(ackCHK[a] != 1){
                                    if(biterror == a){
                                        if(a == finalseqnum){
                                            if(lastlength == 1000){
                                                CHKsum = 0x0000;
                                                ready_retrans[a][1] = (byte) ((CHKsum >> 8) & 0xFF);
                                                ready_retrans[a][2] = (byte) (CHKsum & 0xFF);
                                                biterror = -1;
                                                dataoutToServer.write(ready_retrans[a], 0, ready_retrans[a].length);
                                            }
                                            else{
                                                CHKsum = 0x0000;
                                                ready_retrans[a][1] = (byte) ((CHKsum >> 8) & 0xFF);
                                                ready_retrans[a][2] = (byte) (CHKsum & 0xFF);
                                                biterror = -1;
                                                dataoutToServer.write(ready_retrans[a], 0, 5 + lastlength);
                                            }
                                        }
                                        else{
                                            CHKsum = 0x0000;
                                            ready_retrans[a][1] = (byte) ((CHKsum >> 8) & 0xFF);
                                            ready_retrans[a][2] = (byte) (CHKsum & 0xFF);
                                            biterror = -1;
                                            dataoutToServer.write(ready_retrans[a], 0, ready_retrans[a].length); 
                                        }
                                    }
                                    else{
                                        if(a == finalseqnum){
                                            if(lastlength == 1000){
                                                dataoutToServer.write(ready_retrans[a], 0, ready_retrans[a].length);
                                            }
                                            else{
                                                dataoutToServer.write(ready_retrans[a], 0, 5 + lastlength);
                                            }
                                        }
                                        else{
                                            dataoutToServer.write(ready_retrans[a], 0, ready_retrans[a].length); 
                                        }
                                    }
                                    Thread.sleep(1000);
                                }
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }

                    }

                    class senddata implements Runnable {
                        int a;
                        int finalseqnum;
                        int lastlength;

                        senddata(int i, byte finalseq, int last) {
                            a = i;
                            finalseqnum = finalseq;
                            lastlength = last;
                        }

                        @Override
                        public void run() {
                            try {
                                if(a > finalseqnum){
                                }
                                else{
                                    timer[a] = new Thread(new retrans_timer(a, finalseqnum, lastlength));
                                    timer[a].start();
                                    if(timeout == a){
                                        System.out.print(a + " ");
                                        Thread.sleep(1500);
                                        timeout = -1;
                                        dataoutToServer.write(ready_retrans[a], 0, ready_retrans[a].length);
                                    }
                                    else if(drop == a){
                                        System.out.print(a + " ");
                                        drop = -1;
                                    }
                                    else if(biterror == a){
                                        CHKsum = (short) 0xFFFF;
                                        ready_retrans[a][1] = (byte) ((CHKsum >> 8) & 0xFF);
                                        ready_retrans[a][2] = (byte) (CHKsum & 0xFF);
                                        System.out.print(a + " ");
                                        dataoutToServer.write(ready_retrans[a], 0, ready_retrans[a].length);
                                    }
                                    else{
                                        System.out.print(a + " ");
                                        dataoutToServer.write(ready_retrans[a], 0, ready_retrans[a].length);
                                    }
                                    while(a + win_size <= finalseqnum){
                                        Thread.sleep(100);
                                        if(ackCHK[a] == 1 && sendbase <= a + win_size && a + win_size < sendbase + win_size){
                                            a = a + win_size;
                                            if(a == finalseqnum){
                                                if(lastlength == 1000){
                                                    timer[a] = new Thread(new retrans_timer(a, finalseqnum, lastlength));
                                                    timer[a].start();
                                                    if(timeout == a){
                                                        System.out.print(a + " ");
                                                        Thread.sleep(1500);
                                                        timeout = -1;
                                                        dataoutToServer.write(ready_retrans[a], 0, ready_retrans[a].length);
                                                    }
                                                    else if(drop == a){
                                                        System.out.print(a + " ");
                                                        drop = -1;
                                                    }
                                                    else if(biterror == a){
                                                        CHKsum = (short) 0xFFFF;
                                                        ready_retrans[a][1] = (byte) ((CHKsum >> 8) & 0xFF);
                                                        ready_retrans[a][2] = (byte) (CHKsum & 0xFF);
                                                        System.out.print(a + " ");
                                                        dataoutToServer.write(ready_retrans[a], 0, ready_retrans[a].length);
                                                    }
                                                    else{
                                                        System.out.print(a + " ");
                                                        dataoutToServer.write(ready_retrans[a], 0, ready_retrans[a].length);
                                                    }
                                                }
                                                else{
                                                    timer[a] = new Thread(new retrans_timer(a, finalseqnum, lastlength));
                                                    timer[a].start();
                                                    if(timeout == a){
                                                        System.out.print(a + " ");
                                                        Thread.sleep(1500);
                                                        timeout = -1;
                                                        dataoutToServer.write(ready_retrans[a], 0, 5 + lastlength);
                                                    }
                                                    else if(drop == a){
                                                        System.out.print(a + " ");
                                                        drop = -1;
                                                    }
                                                    else if(biterror == a){
                                                        CHKsum = (short) 0xFFFF;
                                                        ready_retrans[a][1] = (byte) ((CHKsum >> 8) & 0xFF);
                                                        ready_retrans[a][2] = (byte) (CHKsum & 0xFF);
                                                        System.out.print(a + " ");
                                                        dataoutToServer.write(ready_retrans[a], 0, ready_retrans[a].length);
                                                    }
                                                    else{
                                                        System.out.print(a + " ");
                                                        dataoutToServer.write(ready_retrans[a], 0, 5 + lastlength);
                                                    }
                                                }
                                            }
                                            else{
                                                timer[a] = new Thread(new retrans_timer(a, finalseqnum, lastlength));
                                                timer[a].start();
                                                if(timeout == a){
                                                    System.out.print((a) + " "); 
                                                    Thread.sleep(1500);
                                                    timeout = -1;
                                                    dataoutToServer.write(ready_retrans[a], 0, ready_retrans[a].length);
                                                }
                                                else if(drop == a){
                                                    System.out.print((a) + " ");
                                                    drop = -1;
                                                }
                                                else if(biterror == a){
                                                    CHKsum = (short) 0xFFFF;
                                                    ready_retrans[a][1] = (byte) ((CHKsum >> 8) & 0xFF);
                                                    ready_retrans[a][2] = (byte) (CHKsum & 0xFF);
                                                    System.out.print(a + " ");
                                                    dataoutToServer.write(ready_retrans[a], 0, ready_retrans[a].length);
                                                }
                                                else{
                                                    System.out.print((a) + " ");
                                                    dataoutToServer.write(ready_retrans[a], 0, ready_retrans[a].length);
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }

                        }
                        
                    }

                    Thread receiveackmessage = new Thread(new window_shifting(SeqNo));
                    receiveackmessage.start();
                    SeqNo--;
                    if(timeout >= 0){
                        print_var = timeout;
                    }
                    else if(drop >= 0){
                        print_var = drop;
                    }
                    else if(biterror >= 0){
                        print_var = biterror;
                    }

                    for(i = 0; i < win_size; i++){
                        datatrasfer[i] = new Thread(new senddata(i, SeqNo, lastlength));
                        datatrasfer[i].start();
                        Thread.sleep(20);
                    }
                    for(i = 0; i < win_size; i++){
                        datatrasfer[i].join();
                    }
                    receiveackmessage.join();

                    int b = 0;
                    System.out.println("");
                    if(print_var >= 0){
                        for(i = 0; i < ackCHK.length; i++){
                            if(print_var == i){}
                            else if(ackCHK[i] == 1){
                                if(b == SeqNo - 1){
                                    System.out.println(i + " acked");
                                }
                                else{
                                    System.out.print(i + " acked, ");
                                }
                                b++;
                            }
                        }
                        System.out.println(print_var + " timed out & retransmitted");
                    }

                    else{
                        for(i = 0; i < ackCHK.length; i++){
                            if(ackCHK[i] == 1){
                                if(i == SeqNo){
                                    System.out.println(i + " acked");
                                }
                                else{
                                    System.out.print(i + " acked, ");
                                }
                            }
                        }
                    }
                    print_var = -1;
                    fis.close();
                    dataSocket.close();
                }
            }

            //'QUIT' command
            else if(requestmessage.equals("QUIT")){
                clientSocket.close();
                System.exit(0);
            }

            //'DROP' command
            else if (answer[0].equals("DROP")){
                drop = Integer.parseInt(answer[1].replaceAll("[^0-9]", ""));
                System.out.println(drop);
            }

            //'TIMEOUT' command
            else if (answer[0].equals("TIMEOUT")){
                timeout = Integer.parseInt(answer[1].replaceAll("[^0-9]", ""));
                System.out.println(timeout);
            }

            //'BITERROR' command
            else if (answer[0].equals("BITERROR")){
                biterror = Integer.parseInt(answer[1].replaceAll("[^0-9]", ""));
                System.out.println(biterror);
            }
            else{
                System.out.println("Wrong Command");
            }
        }
    }
}