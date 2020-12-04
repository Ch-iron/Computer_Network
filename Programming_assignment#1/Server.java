import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Server {
    static String[] command;
    static Path path = Paths.get("");
    static String present_path = path.toAbsolutePath().toString();

    public static String toAbsolutePath(String path){
        Path isabsolute = Paths.get(path);
        if(isabsolute.isAbsolute()){
            return path;
        }
        else{
            Path absolutePath = Paths.get(present_path + "/" + path);
            return absolutePath.normalize().toString();
        }
    }

    public static Filestat list(String path){
        String fileinfo;
        int i;

        File file = new File(path);
        boolean isExists = file.exists();
     
        if(!isExists) {
            Filestat files = new Filestat(0, "not exist");
            return files;
        }
     
        if(file.isDirectory()) {
            File[] fileList = file.listFiles();
            i = 0;
            fileinfo = fileList[0].getName();
            while(i < fileList.length){
                fileinfo = fileinfo + ",";          
                if(fileList[i].isDirectory()) {
                    if(i == fileList.length - 1){
                        fileinfo = fileinfo + "-";
                    }
                    else{
                        fileinfo = fileinfo + "-,";
                    }
                } else {
                    if(i == fileList.length - 1){
                        fileinfo = fileinfo + fileList[i].length();
                    }
                    else{
                        fileinfo = fileinfo + fileList[i].length() + ",";
                    }
                }
                i++;
                if(i < fileList.length){
                    fileinfo = fileinfo + fileList[i].getName();
                }
            }
                Filestat files = new Filestat(fileList.length, fileinfo);
                return files;       
        } else {
            Filestat files = new Filestat(0, "This is file");
            return files;
        }
    }

    public static int cd(String path){
        //String absolutepath = toAbsolutePath(path);
        File file = new File(path);
        boolean isExists = file.exists();
        if(!isExists){
            return 300;
        }
        if(file.isDirectory()){
            present_path = path;
            return 200;
        }
        else{
            return 400;
        }
    }

    public static int get_status(String path){
        File file = new File(path);

        boolean isExists = file.exists();
    
        if(!isExists) {
            return 300;
        }
    
        if(!file.isDirectory()) {
            return 200;
        } else {
            return 400;
        }
}

    public static void main(String[] args) throws Exception {
        String requestmessage;
        String responsemessage;
        int status;
        byte SeqNo;
        short CHKsum = 0x0000;
        short Size;
        int control_port = 2020;
        int data_port = 2121;
        if(args.length > 0){
            control_port = Integer.parseInt(args[0]);
            data_port = Integer.parseInt(args[1]);
        }

        ServerSocket welcomeSocket = new ServerSocket(control_port);

        System.out.println("Server wait Client.....");
        Socket connectionSocket = welcomeSocket.accept();
        System.out.println("Client Accept!!!");

        while(true){

            BufferedReader inFromClient = 
            new BufferedReader(new InputStreamReader(connectionSocket.getInputStream()));

            DataOutputStream outToClient = 
            new DataOutputStream(connectionSocket.getOutputStream());

            requestmessage = inFromClient.readLine();
            System.out.println("Request: " + requestmessage);

            command = requestmessage.split(" ");
            //System.out.println(command[0]);

            //'LIST' command
            if(command[0].equals("LIST")){
                String path = toAbsolutePath(command[1]);
                Filestat files = list(path);
                if(files.num_file > 0){
                    System.out.println("Response: 200 Comprising " + files.num_file + " entries");
                    responsemessage = "200 Comprising " + files.num_file + " entries";
                    outToClient.writeBytes(responsemessage + '\n');

                    responsemessage = files.fileinfo;
                    outToClient.writeBytes(responsemessage + '\n');
                }
                else if(files.fileinfo.equals("not exist")){
                    System.out.println("Response: 300 Failed - Not Exist");
                    responsemessage = "300 Failed - Not Exist";
                    outToClient.writeBytes(responsemessage + '\n');
                }
                else if(files.fileinfo.equals("This is file")){
                    System.out.println("Response: 400 Failed - Not directory");
                    responsemessage = "400 Failed - Not directory";
                    outToClient.writeBytes(responsemessage + '\n');
                }
            }

            //'CD' command
            else if(command[0].equals("CD")){
                if(command.length < 2){
                    //System.out.println(present_path);
                    System.out.println("Response: 200 Moved to " + present_path);
                    responsemessage = "200 Moved to " + present_path;
                    outToClient.writeBytes(responsemessage + '\n');
                }
                else{
                    String path = toAbsolutePath(command[1]);
                    status = cd(path);
                    if(status == 200){
                        System.out.println("Response: " + status + " Moved to " + present_path);
                        responsemessage = status + " Moved to " + present_path;
                        outToClient.writeBytes(responsemessage + '\n');
                    }
                    else if(status == 300){
                        System.out.println("Response: " + status + " Failed - Not Exist");
                        responsemessage = status + " Failed - Not Exist";
                        outToClient.writeBytes(responsemessage + '\n');
                    }
                    else if(status == 400){
                        System.out.println("Response: " + status + " Failed - Not directory");
                        responsemessage = status + " Failed - Not directory";
                        outToClient.writeBytes(responsemessage + '\n');
                    }
                }
            }

            //'GET' command
            else if(command[0].equals("GET")){
                ServerSocket welcomedataSocket = new ServerSocket(data_port);
                Socket dataSocket = welcomedataSocket.accept();
                OutputStream dataoutToClient = dataSocket.getOutputStream();
                InputStream acksignal = dataSocket.getInputStream();
                byte[] data = new byte[1005];
                //System.out.println("Data trasfer Ready!!!!");

                String path = toAbsolutePath(command[1]);
                status = get_status(path);
                File file  = new File(path);
                Size = (short)file.length();
                SeqNo = 1;
                if(status == 200){
                    FileInputStream fis = new FileInputStream(path);
                    int n;
                    System.out.println("Response: " + status + " Containing " + file.length() + " bytes in total");
                    responsemessage = status + " Containing " + file.length() + " bytes in total";
                    outToClient.writeBytes(responsemessage + '\n');
                    while((n = fis.read(data, 5, 1000)) != -1){
                        data[0] = SeqNo;
                        data[1] = (byte) ((CHKsum >> 8) & 0xFF);
                        data[2] = (byte) (CHKsum & 0xFF);
                        data[3] = (byte) ((Size >> 8) & 0xFF);
                        data[4] = (byte) (Size & 0xFF);
                        if(n == 1000){
                            dataoutToClient.write(data, 0, data.length);
                        }
                        else if(n < 1000){
                            dataoutToClient.write(data, 0, 5 + n);
                        }
                        acksignal.read(data, 0, 3);
                        //byte ack_seq = data[0];
                        //short chk = (short) ((data[1] << 8) | data[2] & 0xFF);
                        //System.out.print("# " + n);
                        //System.out.println("Ack_seq : " + ack_seq + "chk : " + chk);
                        SeqNo++;
                    }
                    fis.close();
                    dataSocket.close();
                    //System.out.println("Completed!!");
                }
                else if(status == 300){
                    System.out.println("Response: " + status + " Failed - Not Exist");
                    responsemessage = status + " Failed - Not Exist";
                    outToClient.writeBytes(responsemessage + '\n');
                    dataSocket.close();
                }
                else if(status == 400){
                    System.out.println("Response: " + status + " Failed - Not file");
                    responsemessage = status + " Failed - Not file";
                    outToClient.writeBytes(responsemessage + '\n');
                    dataSocket.close();
                }
                welcomedataSocket.close();
            }

            //'PUT' command
            else if(command[0].equals("PUT")){
                String path = toAbsolutePath(command[1]);
                ServerSocket welcomedataSocket = new ServerSocket(data_port);
                Socket dataSocket = welcomedataSocket.accept();
                //System.out.println("Data receive Ready!!!!");
                requestmessage = inFromClient.readLine();
                System.out.println("Request: " + requestmessage);
                InputStream datainFromClient = dataSocket.getInputStream();
                OutputStream acksignal = dataSocket.getOutputStream();
                byte[] data = new byte[1005];

                if(Integer.parseInt(requestmessage) == 0){
                    System.out.println("Response : 500 Failed for unknown reason");
                    outToClient.writeBytes("500 Failed for unknown reason" + '\n');
                }
                else if(Integer.parseInt(requestmessage) > 0){
                    System.out.println("Response: 200 Ready to Receive");
                    outToClient.writeBytes("200 Ready to Receive" + '\n');
    
                    FileOutputStream fos = new FileOutputStream(path);
                    int n;
                    while((n = datainFromClient.read(data)) != -1){
                        SeqNo = data[0];
                        CHKsum = (short) ((data[1] << 8) | data[2] & 0xFF);
                        Size = (short) ((data[3] << 8) | data[4] & 0xFF);
                        if(n == 1005){
                            fos.write(data, 5, 1000);
                        }
                        else if(n < 1005){
                            fos.write(data, 5, n - 5);
                        }
                        acksignal.write(data, 0, 3);
                        //System.out.print(SeqNo + " " + CHKsum + " " + Size + " ");
                        //System.out.println("# " + n);
                    }
                    //System.out.println("Completed!!");
                    fos.close();
                }
                dataSocket.close();
                welcomedataSocket.close();
            }
            else if(requestmessage.equals("QUIT")){
                welcomeSocket.close();
                System.exit(0);
            }
            else{
                System.out.println("Wrong Command");
            }
        }
    }
}