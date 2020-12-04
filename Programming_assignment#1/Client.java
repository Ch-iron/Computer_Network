import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Client{
    static Path path = Paths.get("");
    static String present_path = path.toAbsolutePath().toString();
    public static void main(String[] args) throws Exception{
        String requestmessage;
        String responsemessage;
        String[] answer;
        String[] status;
        String[] filelist;
        int i;
        byte SeqNo;
        short CHKsum = 0x0000;
        short Size;
        String ftp_server_host = "127.0.0.1";
        int control_port = 2020;
        int data_port = 2121;
        if(args.length > 0){
            ftp_server_host = args[0];
            control_port = Integer.parseInt(args[1]);
            data_port = Integer.parseInt(args[2]);
        }

        Socket clientSocket = new Socket(ftp_server_host, control_port);

        DataOutputStream outToServer = 
        new DataOutputStream(clientSocket.getOutputStream());

        BufferedReader inFromServer = 
        new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        
        while(true){
            System.out.print("Input Command :");
            BufferedReader inFromUser = 
            new BufferedReader(new InputStreamReader(System.in));
            
            requestmessage = inFromUser.readLine();

            answer = requestmessage.split(" ");

            outToServer.writeBytes(requestmessage + '\n');

            //'LIST' command
            if(answer[0].equals("LIST")){
                responsemessage = inFromServer.readLine();
                //System.out.println(responsemessage);

                status = responsemessage.split(" ");
                if(status[0].equals("200")){
                    responsemessage = inFromServer.readLine();
                    //System.out.println(responsemessage);
                    filelist = responsemessage.split(",");
                    for(i = 0; i < filelist.length; i += 2){
                        System.out.println(filelist[i] + "," + filelist[i + 1]);
                    }
                }
                else if(status[0].equals("300")){
                    System.out.println("Failed - Not Exist");
                }
                else if(status[0].equals("400")){
                    System.out.println("Failed - Not directory");
                }
                //continue;
            }

            //'CD' command
            else if(answer[0].equals("CD")){
                responsemessage = inFromServer.readLine();
                System.out.println(responsemessage);

                status = responsemessage.split(" ");
                if(status[0].equals("200")){
                    System.out.println(status[3]);
                }
                else if(status[0].equals("300")){
                    System.out.println("Failed - Not Exist");
                }
                else if(status[0].equals("400")){
                    System.out.println("Failed - Not directory");
                }
            }

            //'GET' command
            else if(answer[0].equals("GET")){
                Socket dataSocket = new Socket(ftp_server_host, data_port);
                responsemessage = inFromServer.readLine();
                //System.out.println(responsemessage);
                InputStream datainFromServer = dataSocket.getInputStream();
                OutputStream acksignal = dataSocket.getOutputStream();
                byte[] data = new byte[1005];

                status = responsemessage.split(" ");
                if(status[0].equals("200")){
                    Path path = Paths.get(answer[1]);
                    System.out.println("Received " + path.getFileName() + "/ "  + status[2] + "bytes");
                    FileOutputStream fos = new FileOutputStream(present_path + "/" + path.getFileName().toString());
                    int n;
                    while((n = datainFromServer.read(data)) != -1){
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
                        System.out.print("# ");
                    }
                    System.out.println("Completed!!");
                    fos.close();
                }
                else if(status[0].equals("300")){
                    System.out.println("Failed - Not Exist");
                }
                else if(status[0].equals("400")){
                    System.out.println("Failed - Not file");
                }
                dataSocket.close();
            }

            //'PUT' command
            else if(answer[0].equals("PUT")){
                Socket dataSocket = new Socket(ftp_server_host, data_port);
                OutputStream dataoutToServer = dataSocket.getOutputStream();
                InputStream acksignal = dataSocket.getInputStream();
                byte[] data = new byte[1005];

                File file = new File(present_path + "/"  + answer[1]);
                Size = (short)file.length();
                SeqNo = 1;

                if(!file.exists()){
                    System.out.println("Failed - Not Exist");
                    requestmessage = Integer.toString(0);
                    outToServer.writeBytes(requestmessage + '\n');
                    dataSocket.close();
                    continue;
                }
                if(file.isDirectory()){
                    System.out.println("Failed - Not file");
                    requestmessage = Integer.toString(0);
                    outToServer.writeBytes(requestmessage + '\n');
                    dataSocket.close();
                    continue;
                }
                
                requestmessage = Long.toString(file.length());
                outToServer.writeBytes(requestmessage + '\n');

                responsemessage = inFromServer.readLine();
                //System.out.println(responsemessage);

                status = responsemessage.split(" ");
                if(status[0].equals("500")){
                    System.out.println("Failed for unknown reason");
                    dataSocket.close();
                }
                else{
                    System.out.println(answer[1] + " transferred /" + file.length() + " bytes");
    
                    FileInputStream fis  = new FileInputStream(present_path + "/" + answer[1]);
                    int n;
                    while((n = fis.read(data, 5, 1000)) != -1){
                        data[0] = SeqNo;
                        data[1] = (byte) ((CHKsum >> 8) & 0xFF);
                        data[2] = (byte) (CHKsum & 0xFF);
                        data[3] = (byte) ((Size >> 8) & 0xFF);
                        data[4] = (byte) (Size & 0xFF);
                        if(n == 1000){
                            dataoutToServer.write(data, 0, data.length);
                        }
                        else if(n < 1000){
                            dataoutToServer.write(data, 0, 5 + n);
                        }
                        acksignal.read(data, 0, 3);
                        //byte ack_seq = data[0];
                        //short chk = (short) ((data[1] << 8) | data[2] & 0xFF);
                        System.out.print("# ");
                        //System.out.println("Ack_seq : " + ack_seq + "chk : " + chk);
                        SeqNo++;
                    }
                    System.out.println("Completed!!");
                    fis.close();
                    dataSocket.close();
                }
            }
            else if(requestmessage.equals("QUIT")){
                clientSocket.close();
                System.exit(0);
            }
            else{
                System.out.println("Wrong Command");
            }
        }
    }
}