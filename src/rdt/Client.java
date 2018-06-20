package rdt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import huffman.HuffmanCompressor;

public class Client extends Thread{
	InetAddress serverIP;
	short serverPort;
	short serverACKPort;
	InetAddress clientIP;
	short clientPort;
	String fileName;
	int lossProbability = 30;
	int corruptProbability = 30;
	int packetSize = 1000;
	DatagramSocket recivingSocket;
	DatagramSocket sendingSocket;
	ConcurrentLinkedQueue<ACKPacket> ackQueue = new ConcurrentLinkedQueue<ACKPacket>();
	CopyOnWriteArrayList<Short> recivedPacketNumbers = new CopyOnWriteArrayList<Short>();
	ConcurrentLinkedQueue<TCPPacket> recivedPackets = new ConcurrentLinkedQueue<TCPPacket>();
	
	public Client() {
		try {
			this.loadClientFromFile();
			recivingSocket = new DatagramSocket(this.clientPort);
			sendingSocket = new DatagramSocket();
		} catch(Exception e) {
			e.printStackTrace();
		}
	}
	
	public Client(String serverIP, short serverPort, String clientIP, short clientPort, String fileName) {
		try {
			this.serverIP = InetAddress.getByName(serverIP);
			this.serverPort = serverPort;
			this.clientIP = InetAddress.getByName(clientIP);
			this.clientPort = clientPort;
			this.fileName = fileName;
		
			// Two sockets one for sending ACKs and one for receiving data from server.
			recivingSocket = new DatagramSocket(this.clientPort);
			sendingSocket = new DatagramSocket();
		} catch (SocketException e) {
			e.printStackTrace();
		} catch (UnknownHostException e) {
			e.printStackTrace();
		} 
		
	}

	@Override
	public void run() {
		try {
			ClientDataHandler handler = new ClientDataHandler();   // handles receiving data.
			ClientACKHandler ackHandler = new ClientACKHandler();  // handles sending ACKs.
			
			// Create a new packet and add the needed file name to it.
			byte[] fileNameBuffer = this.fileName.getBytes();
			TCPPacket filePacket = new TCPPacket(this.clientPort, this.serverPort,(short) 0, fileNameBuffer);
			filePacket.setFileName(this.fileName);
			byte[] filePacketBytes = filePacket.encode();
			DatagramPacket fileNamePacket = new DatagramPacket(filePacketBytes, filePacketBytes.length, serverIP, this.serverPort);
			
			handler.start();
			ackHandler.start();
			
			// Keep sending the packet every 5 seconds until the handler starts receiving.
			while(!handler.isReciving()) {
				this.sendingSocket.send(fileNamePacket);
				System.out.println("client sent file name");
				Thread.sleep(2000);
			}
			
			// wait for both handlers to finish before exiting.
			handler.join();
			ackHandler.join();
			writeFile();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public void writeFile() {
		ArrayList<Byte> byteArrayList = new ArrayList<Byte>();
		ArrayList<TCPPacket> filePackets = new ArrayList<TCPPacket>();
		HuffmanCompressor huffman = new HuffmanCompressor();
		
		while(!this.recivedPackets.isEmpty()) {
			filePackets.add(this.recivedPackets.poll());
		}
		
		filePackets.sort(null);
		
		for (int i = 0; i < filePackets.size(); i++) {
			byte[] bytes = filePackets.get(i).getData();
			for (int j = 0; j < bytes.length; j++) {
				byteArrayList.add(bytes[j]);
			}
		}
		
		byte[] byteArray = new byte[byteArrayList.size()];
		for (int i = 0; i < byteArrayList.size(); i++) {
			byteArray[i] = byteArrayList.get(i).byteValue();
		}
		
		File sourceFile = new File(this.fileName);
		String fileName = sourceFile.getName().substring(0, sourceFile.getName().lastIndexOf("."));
		
		try {
			Path path = Paths.get("R" +  fileName + "-compressed.txt");
			Files.write(path, byteArray);
			huffman.deCommpressFile("R" +  fileName + "-compressed.txt");
		} catch(Exception e) {
			e.printStackTrace();
		}
	}
	
	// randomly drop packet.
	private boolean dropPacket(int lossProbability) {
		Random randomNumberGenerator = new Random();
		int randomNumber = randomNumberGenerator.nextInt(100);
		
		if (randomNumber <= lossProbability) {
			return true;
		}
		
		return false;
	}
	
	// randomly corrupt packet.
	private boolean corruptPacket(int corruptProbability) {
		Random randomNumberGenerator = new Random();
		int randomNumber = randomNumberGenerator.nextInt(100);
		
		if (randomNumber <= corruptProbability) {
			return true;
		}
		
		return false;
	}
	
	public void setLossProbability(int lossProbability) {
		this.lossProbability = lossProbability;
	}

	public void setCorruptProbability(int corruptProbability) {
		this.corruptProbability = corruptProbability;
	}

	public void loadClientFromFile() {
		String tempString;
		
		try {
			FileReader file = new FileReader("client.txt");
			BufferedReader read = new BufferedReader(file);
			
			tempString = read.readLine();
			this.serverIP = InetAddress.getByName(tempString);
			
			tempString = read.readLine();
			this.serverPort = Short.parseShort(tempString);
			
			tempString = read.readLine();
			this.clientIP = InetAddress.getByName(tempString);
			
			tempString = read.readLine();
			this.clientPort = Short.parseShort(tempString);
			
			tempString = read.readLine();
			this.fileName = tempString;
			
			tempString = read.readLine();
			this.lossProbability = Integer.parseInt(tempString);
			
			tempString = read.readLine();
			this.corruptProbability = Integer.parseInt(tempString);
			
			read.close();
		} catch(Exception e) {
			e.printStackTrace();
		}
	}
	
	private class ClientDataHandler extends Thread {
		boolean reciving = false;
		boolean lastPacketRecived = false;
		boolean allPacketsReceived = false;
		short lastPacketNumber;
		
		@Override
		public void run() {
			try {
				// Wait for the first packet to be received.
				byte[] packetBuffer = new byte[packetSize + 12];
				DatagramPacket recivedPacket = new DatagramPacket(packetBuffer, packetBuffer.length);
				recivingSocket.receive(recivedPacket);
				
				// set the flag to stop requesting the file from the server.
				this.reciving = true;
				recivePacket(recivedPacket);
				
				// Keep receiving the data packet until the last packet is received.
				while(!lastPacketRecived || !allPacketsReceived) {
					packetBuffer = new byte[packetSize + 12];
					recivedPacket = new DatagramPacket(packetBuffer, packetBuffer.length);
					recivingSocket.receive(recivedPacket);
					recivePacket(recivedPacket);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		public void recivePacket(DatagramPacket recivedDatagram) {
			// Decode the received packet.
			byte[] encodedData = recivedDatagram.getData();
			TCPPacket recivedPacket = new TCPPacket();
			recivedPacket.decode(encodedData);
			
			if (dropPacket(lossProbability)) {
				System.out.println("packet " + recivedPacket.getSequanceNumber() + " lost");
				return;
			}
			else if (corruptPacket(corruptProbability)) {
				System.out.println("packet " + recivedPacket.getSequanceNumber() + " corrupted");
				recivedPacket.setCheckSum((short) -1);
			}
			
			if (!recivedPacket.isCorrupted()) {
				System.out.println("client received packet " + recivedPacket.getSequanceNumber());
				
				// Create a new ACK packet.
				short sequanceNumber = recivedPacket.getSequanceNumber();
				ACKPacket ackPacket = new ACKPacket(sequanceNumber, clientPort);
				
				// Add the packet if not already received.
				if (!recivedPacketNumbers.contains(sequanceNumber)) {
					recivedPacketNumbers.add(sequanceNumber);
					recivedPackets.offer(recivedPacket);
				}
				
				// Set the flag if it is the last  packet.
				if (recivedPacket.isFinalPacket()) {
					lastPacketRecived = true;
					ackPacket.setFinalACKPacket(true);
					this.lastPacketNumber = sequanceNumber;
				}
				
				if (this.lastPacketRecived) {
					if (recivedPacketNumbers.size() == lastPacketNumber + 1) {
						this.allPacketsReceived = true;
					}
				}
				
				// Add packet to queue.
				ackQueue.offer(ackPacket);
			}
		}
		
		public boolean isReciving() {
			return reciving;
		}
	}
	
	private class ClientACKHandler extends Thread{
		boolean lastACKPacketSent = false;
		boolean allACKPacketSent = false;
		short lastACKPacketNumber;
		
		@Override
		public void run() {
			try {
				while(!lastACKPacketSent || !allACKPacketSent) {
					if (ackQueue.isEmpty()) {
						continue;
					}
					// get next ACK packetm encode it and send it.
					ACKPacket ackPacket = ackQueue.poll();
					byte[] buffer = ackPacket.encode();
					DatagramPacket Packet = new DatagramPacket(buffer, buffer.length, serverIP, serverPort);
					sendingSocket.send(Packet);
					System.out.println("client sent ACK packet " + ackPacket.getAckNumber());
					
					// If final packet sent break.
					if (ackPacket.isFinalACKPacket()) {
						this.lastACKPacketSent = true;
						this.lastACKPacketNumber = ackPacket.getAckNumber();
					}
					
					if (lastACKPacketSent) {
						if (recivedPacketNumbers.size() == lastACKPacketNumber + 1) {
							this.allACKPacketSent = true;
						}
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		
	}
}
