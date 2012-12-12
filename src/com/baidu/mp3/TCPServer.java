package com.baidu.mp3;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.LinkedList;
import java.util.List;

import javax.media.Manager;
import javax.media.MediaLocator;
import javax.media.NoPlayerException;
import javax.media.Player;

import org.apache.log4j.Logger;

public class TCPServer implements Runnable {

	public static final int SERVERPORT = 51706;
	public static final String REPO = "songs";
	public static final String ARCHIEVE = "backup/";
	public static final String EXT = ".mp3";
	public static final String WAV_EXT = ".wav";
	public static final long TIME_SPAN = 5 * 60 * 1000;  // the max time for a song

	public static final String START_MES = "^";
	public static final String STOP_MES = "&$";
	public static final String GOT_IT = "%";
	public static final long BEAT_INTERVAL = 10000; //ms
	public static final long PROCESS_CHECK_INTERVAL = 500;

	private static Logger log = Logger.getLogger(TCPServer.class);
	private List<File> songList = new LinkedList<File>();
	private ServerSocket ss;
	private Socket socket;

	private enum STATE {
		EMPTY, INIT, PLAY, STOP, TERMINATE;
	}

	public STATE state = STATE.EMPTY;
	private boolean processRun = true;

	@Override
	public void run() {
		while (true) {
			switch (state) {
			case EMPTY:
				init();
				break;
			case INIT:
				waitForStart();
				break;
			case PLAY:
				start();
				break;
			case STOP: // this is unreachable
				break;
			case TERMINATE:
				log.info("Server terminated");
				return;
			}
		}

	}

	/**
	 * Play the song that passed in, and send heart beat
	 * @param f
	 * @throws IOException
	 * @throws InterruptedException 
	 */
	private void playSong(File f, PrintWriter pw) throws IOException, InterruptedException {
		MediaLocator ml = new MediaLocator(f.toURI().toURL());
		long maxTimeSpan = TIME_SPAN;
		try {
			Player p = Manager.createPlayer(ml);
			p.start();
			
			long duration = p.getDuration().getNanoseconds() / 1000000;
			log.info("Define max time span 5 minutes");
			log.info("Detect sont duration: "
					+ duration);
			long retDuration  = duration > maxTimeSpan ? maxTimeSpan : duration ;
			log.info("The actual duration is : " + retDuration);
			
			log.info("Enter heart beat function, will sleep for (ms) " + retDuration);
			long heartBeat = BEAT_INTERVAL;
			int beatTime = (int)(retDuration / heartBeat);
			for (int i=0; i< beatTime; i++){
				Thread.sleep(heartBeat);
				log.debug("send heart beat");
				pw.println("__bt");
			}
			Thread.sleep(duration % heartBeat); 
			log.info("wake up.");
			p.stop();
			p.close();
			
		} catch (NoPlayerException e1) {
			log.error("No player error : " + f.getAbsolutePath());
			e1.printStackTrace();
		} 
	}
	
	/**
	 * Not JMF but extra command to play the music.
	 * @param f
	 * @param pw
	 */
	private void playSongByCmd(File f, PrintWriter pw) {
		String filePath = f.getAbsolutePath();
		log.info("detect mp3 file absolute path: "+ filePath);
		long timespan = TIME_SPAN;
		processRun = true;
		String[] command = new String[] { "bash", "-c","mplayer "+filePath };
//		String[] command = new String[]{"c:/users/dongbo/a.bat"}; // for test a span of 30 sec
		Process process;
		try {
			process = Runtime.getRuntime().exec(command);
			new BeatThread(timespan, process, pw).start();
			new ExeCom(process.getInputStream()).start();
			process.waitFor();
		} catch (IOException e) {
			log.error("process met error");
		} catch (InterruptedException e) {
			log.info("process interrupted");
		}
		log.debug("Process end");
		processRun = false;
		try {
			Thread.sleep(2*PROCESS_CHECK_INTERVAL); //  wait for the beat dead;
		} catch (InterruptedException e) {
			log.error("sleep error");
		} 
        log.info("song play end");
	}
	
	//This is for short wave file only, it'll not send a heart beat;
	private void playWav(File file){
		MakeSound ms = new MakeSound();
		ms.playSound(file);
	}
	
	

	/**
	 * the state of begining. initialize all the files and others
	 * 
	 * @return if initial success;
	 * @throws IOException
	 */
	private void init() {
		File file = new File(REPO);
		if (!file.exists() || !file.isDirectory()) {
			log.error("Init: The containing folder doesn't exist or is not directory! "
					+ REPO);
			state = STATE.TERMINATE;
			return;
		}
		File[] files = file.listFiles();

		for (File singleF : files) {
			if (!(singleF.getName().endsWith(".mp3")
					|| singleF.getName().endsWith(".MP3")
					|| singleF.getName().endsWith(".wav") || singleF.getName()
					.endsWith(".WAV")))
				continue;
			songList.add(singleF);
		}
		log.info("Initial finished with: song number: " + songList.size());
		try {
			ss = new ServerSocket(SERVERPORT);
			
		} catch (IOException e) {
			log.error("Server socket initial error",e);
			state = STATE.TERMINATE;
			return;
		}
		log.info("Initial Server port on : " + SERVERPORT);
		this.state = STATE.INIT;
	}

	// in the state of waiting; get ready of everything.
	private void waitForStart() {
		
		if (songList.isEmpty()) {
			log.info("Song list empty; will exit");
			state = STATE.TERMINATE;
			return;
		}
		while (true) {
			try {
				log.debug("waiting to listen...");
				//TODO add socket lost protection
				socket = ss.accept();
				BufferedReader br = new BufferedReader(new InputStreamReader(
						socket.getInputStream()));
				String str = br.readLine();
				log.info("S: receieved: " + str);
				if (str != null && START_MES.equals(str.trim())) {
					Thread.sleep(400); // Sleep a little longer than the phone
					log.info("Return to play: ");
					this.state = STATE.PLAY;
					return ;
				}
			} catch (IOException e) {
				log.error("Encount error but will continue", e);
				e.printStackTrace();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} 
		}
	}

	// in the state of start; will play song and and record/ stop command;
	private void start() {
		if (songList.isEmpty()) {
			log.error("Song list empty; will exit");
			state = STATE.TERMINATE;
			return;
		}
		PrintWriter pw;
		try {
			pw = new PrintWriter(socket.getOutputStream(), true);
		} catch (IOException e) {
			log.error("Socket error at play:", e);
			state = STATE.TERMINATE;
			return;
		}
		
		File file = songList.get(0);
		String fileName = file.getName().substring(0,
				file.getName().length() - 4);
		log.info("Now playing: " + fileName);
		log.info("Sending name:" + fileName);
		pw.println(fileName);
		
		
		try {
			Thread.sleep(500); // create a little buffer
			if(file.getName().endsWith(".mp3") || file.getName().endsWith(".MP3"))
				playSongByCmd(file,pw);
			else
				playWav(file);
		} catch (InterruptedException e) {
			log.error("Interrupt Error:", e);
		}
		log.info("Play finish, send stop..");
		try {
			pw = new PrintWriter(socket.getOutputStream(), true);
			pw.println(STOP_MES);
			Thread.sleep(700); // make sure it's dead
			pw.println(STOP_MES); // maybe it'll just discard this, but who knows previous one is good?
			File doneSong = songList.remove(0);
			
			pw.close();
			end(doneSong);
		} catch (IOException e) {
			log.error("Socket error at play:", e);
			state = STATE.TERMINATE;
			return;
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		state = STATE.INIT;
	}

	/**
	 * The function of end;
	 * 
	 */
	private void end(File file) {
		moveToArchieve(file);
	}
	
	/**
	 * Move the file from origin folder to backup
	 * @param file The file name that just recorded
	 */
	private void moveToArchieve(File file){
		String newFilePath = ARCHIEVE;
		File newPath = new File(newFilePath);
		if(!newPath.exists())
			newPath.mkdirs();
		File nFile = new File(newFilePath + file.getName());
		boolean succ = file.renameTo(nFile);
		if(succ)
			log.info("back up to :" + nFile.getAbsolutePath());
		else{
			log.info("Fail Moving :" + nFile.getAbsolutePath());
		}
	}
	
	private void terminte(){
		try {
			socket.close();
			ss.close();
		} catch (IOException e) {
			log.error("Error in terminate, anyway");
		}
	}
	
	class ExeCom extends Thread {
		InputStream is;

		ExeCom(InputStream is) {
			this.is = is;
		}

		public void run() {
			try {
				InputStreamReader isr = new InputStreamReader(is);
				BufferedReader br = new BufferedReader(isr);
				String line = null;
				while ((line = br.readLine()) != null) {
					//just do nothing
				}
			} catch (IOException ioe) {
				//ignore
			}
		}
	}
	
	class BeatThread extends Thread{
		

		private long timeSpan;
		private Process process;
		PrintWriter pw;
		
		BeatThread(long timeSpan, Process process, PrintWriter pw){
			this.timeSpan = timeSpan;
			this.process = process;
			this.pw = pw;
		}
		
		@Override
		public void run() {
			try {
				long beatInterval = BEAT_INTERVAL;
				long checkInteral = PROCESS_CHECK_INTERVAL;
				long step = beatInterval / checkInteral;
				long time = timeSpan / beatInterval;
				for (int i =0; i < time; i++){
					for (int j = 0; j < step; j++){
						Thread.sleep(checkInteral);
						if(!processRun) return; //just quit
					}
					log.debug("send heart beat");
					pw.println("__bt");
				}
				log.info("Meet time max span(ms): " + timeSpan);
				process.destroy();
			} catch (InterruptedException e) {
				e.printStackTrace();
			} 	
		}
	}
	

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		Thread t = new Thread(new TCPServer());
		t.start();
	}

}
