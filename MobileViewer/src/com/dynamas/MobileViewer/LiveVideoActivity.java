/*
Filename: LiveVideoActivity.java
Purpose:
Description:
Notice:
Revision History:
  July 24, 2012 (First created)
  Nov 30, 2012 (輸入的網路位址可以指定port number，例如：dynamas.com.tw:17860)

工作事項：
*/
package com.dynamas.MobileViewer;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.SurfaceHolder.Callback2;
import android.view.*;
import android.util.Log;
import android.util.DisplayMetrics;
import android.widget.FrameLayout;
import java.net.*;
import java.io.*;
import java.util.*;
// audio
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
/*
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.SurfaceHolder.Callback2;
import android.view.*;
import android.util.Log;
import android.util.DisplayMetrics;
import android.widget.FrameLayout;
import java.net.*;
import java.io.*;
*/

public class LiveVideoActivity extends Activity implements Runnable {
    private static final String DTag = "LVA";
    private LiveVideoSurfaceView view_desktop;

	// 網路訊息id的定義
    final int NM_ReturnValue = 0;
    final int NM_CheckAlive = 1;
    final int NM_Login = 103;
    final int NM_SendMessage = 106;
    final int NM_StartChannel = 205;
    final int NM_StopChannel = 206;
    final int NM_SendVideoFrame = 207;
    final int NM_StartChannel2 = 250;
    final int NM_GetNodeRuntimeInfo = 816;
    final int NM_QueryCom = 901;
	// audio
	final int NM_StartAudioChannel = 210;
	final int NM_StopAudioChannel = 211;
	final int NM_SendAudioSample = 212;
	final int MFormat_JPEG = 5;
	final int MFormat_ADPCM = 9;
	final int MFormat_MP3 = 10;

    private Socket client_s;
    private DataInputStream net_in;
    private DataOutputStream net_out;
    private String remote_addr_id = new String();
    private String remote_user_name = new String();
    private String remote_password = new String();
    private int privilege_level = -1, video_channel_count = 1;
    private static int channel_n = 0, next_channel_n = 0;
    private String channel_name = new String();
    private String channel_desc = new String();
	int audio_in_bufsize;
	AudioRecord	audio_recorder = null;
	LiveAudioPlayer aud_player = null;
    // 給DrawImage()用
    private int image_w, image_h;
    // 避免一直重複配置刪除記憶體（給網路程式使用）
    private byte[] in_header = new byte[8], out_header = new byte[8];
    private byte[] in_data = new byte[256 * 1024];
	AudioCodec acodec = new AudioCodec();	// for EncodeADPCM
	
    Object thread_s = new Object();
    Thread inst_thread, aud_thread;
    private boolean terminate_flag;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        DisplayMetrics dm;

        dm = getResources().getDisplayMetrics();
        //取得螢幕顯示的資料
        if (dm.widthPixels < dm.heightPixels) // 正常情況手機是直立的
        {
            setContentView(R.layout.live_video);
        } else {
            setFullScreen();
            this.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            // 讓手機不要自動關閉螢幕

            setContentView(R.layout.live_video_portrait);
        }
        Bundle bundle = this.getIntent().getExtras();

        remote_addr_id = bundle.getString("v_address");
        remote_user_name = bundle.getString("v_user_name");
        remote_password = bundle.getString("v_password");

        view_desktop = new LiveVideoSurfaceView(this);

        FrameLayout f_layout;

        f_layout = (FrameLayout) findViewById(R.id.lv_view_desktop);
        f_layout.addView(view_desktop);
		aud_player = new LiveAudioPlayer(view_desktop);
        aud_thread = new Thread(aud_player);
		audio_in_bufsize = AudioRecord.getMinBufferSize(8000,
                AudioFormat.CHANNEL_CONFIGURATION_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
		audio_recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
		         8000,
		         AudioFormat.CHANNEL_CONFIGURATION_MONO,
		         AudioFormat.ENCODING_PCM_16BIT,
		         audio_in_bufsize);
		audio_recorder = null;
        inst_thread = new Thread(this);
        StartUp();
    }

    @Override
    protected void onDestroy() {
        CleanUp();
        super.onDestroy();
    }

    private void reset_data() {
        client_s = null;
        net_in = null;
        net_out = null;
        privilege_level = -1;
        video_channel_count = 1;
        //    channel_n=0;    next_channel_n=0;
    }

    private void setFullScreen() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        // 視窗的："Dynamas Mobile Viewer"會不見
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
        WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // 螢幕的最上方：時間、電池容量等空間會不見
    }

    public boolean StartUp() {
        Log.d(DTag, "StartUp()");

        reset_data();

        synchronized(thread_s) {
            terminate_flag = false;
        }

        aud_thread.start();
        inst_thread.start();
        return (true);
    }

    public void CleanUp() {
        Log.d(DTag, "CleanUp()");
		aud_player.setRunning(false);
		
        synchronized(thread_s) {
            terminate_flag = true;
        }

        try {
            inst_thread.join(10 * 1000); // 最多僅等待10秒鐘
            //      inst_thread.join();
        } catch (InterruptedException e) {
            // If another thread has interrupted the current thread
        }
        if (inst_thread.isAlive()) {
            Log.d(DTag, "Interrupting thread...");
            inst_thread.interrupt(); // 若10秒鐘後thread還沒有返回就強迫終止
        }
    }

    public void run() {
        Resources res = getResources();

        inner_run();
        DrawText(res.getString(R.string.Str_Disconnected));
    }

    public void inner_run() {
        Resources res = getResources();
        String remote_addr;
        int remote_port_n = 17860; // 預設值
        int temp_index;

        // 剖析輸入的網路位址
        if ((temp_index = remote_addr_id.indexOf(':')) == -1) {
            remote_addr = remote_addr_id;
        } else {
            String port_n_str;

            remote_addr = remote_addr_id.substring(0, temp_index);
            port_n_str = remote_addr_id.substring(temp_index + 1);
            remote_port_n = Integer.parseInt(port_n_str);
        }

        DrawText(res.getString(R.string.Str_Connecting));
        if (StdConnect(remote_addr, remote_port_n)) {
            try {
                privilege_level = Login(remote_user_name, remote_password);
                if (privilege_level < 0) {
                    Log.d(DTag, "Login failed!");
                    Disconnect();
                    return;
                } else {
                    Log.d(DTag, "Logined with privilege_level: " + privilege_level);
                }
                GetNodeRuntimeInfo(0, 0);
                if (channel_n >= video_channel_count) // 檢查原本已存在channel_n數值的範圍
                {
                    channel_n = next_channel_n = 0;
                }
                // 開啟通道
                if (StartChannel(channel_n, 5)) // 選擇"MF_JPEG"！
                {
                    Log.d(DTag, "Start channel " + (channel_n + 1) + " successfully");
                    DrawText("");
                } else {
                    DrawText(res.getString(R.string.Str_Startchannel) + " " + (channel_n + 1) + " " + res.getString(R.string.Str_failed));
                }
            } catch (IOException e) { // 網路連線中斷...
                return;
            }
        } else {
            return;
        }
        for (;;) {
            // 檢查是否需要離開執行緒
            synchronized(thread_s) {
                if (terminate_flag) break;
            }

            try {
                if (ReadNM()) // 沒事就去檢查有無封包需要讀取
                {
                    try {
                        Thread.sleep(1000 / 60); // 依然釋放CPU資源...
                    } catch (InterruptedException e) {}
                } else // 目前沒有封包可以讀取
                {
                    try {
                        Thread.sleep(1000 / 15);
                    } catch (InterruptedException except) {}
                }

                boolean compare_result;
                int temp_channel_n;

                synchronized(thread_s) {
                    compare_result = next_channel_n != channel_n;
                    temp_channel_n = next_channel_n;
                }
                if (compare_result) {
                    StopChannel(channel_n);
                    synchronized(thread_s) {
                        channel_n = temp_channel_n;
                    }
                    if (StartChannel(channel_n, 5)) {
                        DrawText("");
                    } else {
                        DrawText(res.getString(R.string.Str_Startchannel) + " " + (channel_n + 1) + " " + res.getString(R.string.Str_failed));
                    }
                }
            } catch (IOException e) { // 網路連線中斷...
                return;
            }
        }
        try {
            StopChannel(channel_n);
        } catch (IOException e) { // 網路連線中斷...
            return;
        }
        Disconnect();
    }


    public void action_prev_channel(View view) {
        next_channel_n = (next_channel_n + video_channel_count - 1) % video_channel_count;
    }
    public void action_next_channel(View view) {
        next_channel_n = (next_channel_n + 1) % video_channel_count;
    }

    void DrawImage(int channel_index, int width, int height, String channel_name, Bitmap bitmap) {
        view_desktop.DrawImage(channel_index, width, height, channel_name, bitmap);
    }

    void DrawText(String output_text) {
        view_desktop.DrawText(output_text);
    }

    /*---------------------------------------------------------+
          網路介面函數					
	+---------------------------------------------------------*/

    synchronized boolean StdConnect(String addr_id, int port_n) {
        if (addr_id.contains(".")) // 根據：'.'去判斷是否要透過名稱伺服器去查詢
        {
            return (Connect(addr_id, port_n));
        } else {
            final String ns_addr = "ans.dynamas.com.tw";

            try {
                if (Connect(ns_addr, 17862)) {
                    AddressInfo ai = new AddressInfo();

                    if (QueryCom(addr_id, ai)) {
                        Disconnect();
                        return (Connect(ai.IPAddress, ai.PortN));
                    }
                }
            } catch (IOException e) {
                return (false);
            }
            return (false);
        }
    }

    synchronized boolean Connect(String address, int port_n) {
        InetAddress addr;

        try {
            addr = InetAddress.getByName(address);
        } catch (UnknownHostException e) {
            // If no IP address for the host could be found...
            Log.d(DTag, "Can not resolve address");
            return (false);
        }
        try {
            Log.d(DTag, "Connecting to " + addr + " ...");
            client_s = new Socket(addr, port_n);
            Log.d(DTag, "Connect successfully.");
        } catch (IOException e) {
            Log.d(DTag, "Fail to connect!");
            return (false);
        }
        try {
            //      client_s.setSoLinger( true, 10 );    // 中斷連線時可能較會卡住？
            //      client_s.setTcpNoDelay( true );
            client_s.setKeepAlive(true);
        } catch (SocketException e) { // 應該不會發生！
            try {
                client_s.close();
            } catch (IOException except) {}
            client_s = null;
            return (false);
        }
        try {
            net_in = new DataInputStream(client_s.getInputStream());
            net_out = new DataOutputStream(client_s.getOutputStream());
        } catch (IOException e) { // 應該不會發生！
            try {
                client_s.close();
            } catch (IOException except) {}
            client_s = null;
            net_in = null;
            net_out = null;
            return (false);
        }
        return (true);
    }

    synchronized boolean Disconnect() {
        boolean rv = true;

        Log.d(DTag, "Disconnecting...");

        if (client_s == null) return (false);

        try {
            client_s.close();
        } catch (IOException e) {
            rv = false;
        }
        reset_data();

        if (rv) {
            Log.d(DTag, "Disconnect successfully");
        } else {
            Log.d(DTag, "Fail to disconnect!");
        }
        return (rv);
    }


    // 會自動處理name與password超過限定長度的情形
    synchronized boolean QueryCom(String name, AddressInfo rv_address_info) throws IOException {
        byte[] param_status = new byte[4];
        byte[] param_name;
        int ii;

        if (client_s == null) throw new IOException();

        if (name.length() >= 64) {
            shut_down();
            throw new IOException();
        }
        Tools.ToArray(out_header, 0, 4 + 4 + 64);
        Tools.ToArray(out_header, 4, NM_QueryCom);
        try {
            // 輸出header
            net_out.write(out_header);
            // 輸出Status
            Tools.ToArray(param_status, 0, 0);
            net_out.write(param_status);
            // 輸出name
            param_name = name.getBytes();
            net_out.write(param_name);
            for (ii = param_name.length; ii < 64; ii++)
            net_out.writeByte(0);
            // 接收結果
            for (;;) {
                if (!read_nm()) break; // 等到return value了
            }
        } catch (IOException e) {
            shut_down();
            throw e;
        }

        if (Tools.ToInt(in_data, 0) != 0) {
            rv_address_info.IPAddress = new String(in_data, 4 + 4, Tools.Strlen(in_data, 4 * 2));
            rv_address_info.PortN = Tools.ToInt(in_data, 4 + 4 + 32);
            Log.d(DTag, "QueryCom()= " + rv_address_info.IPAddress + ":" + rv_address_info.PortN);
            return (true);
        } else return (false);
    }


    // 會自動處理name與password超過限定長度的情形
    synchronized int Login(String name, String password) throws IOException {
        byte[] login_data;
        int ii;

        if (client_s == null) throw new IOException();

        if (name.length() >= 64 || password.length() >= 64) {
            shut_down();
            throw new IOException();
        }
        Tools.ToArray(out_header, 0, 4 + 64 + 64);
        Tools.ToArray(out_header, 4, NM_Login);
        try {
            // 輸出header
            net_out.write(out_header);
            // 輸出name
            login_data = name.getBytes();
            net_out.write(login_data);
            for (ii = login_data.length; ii < 64; ii++)
            net_out.writeByte(0);
            // 輸出password
            login_data = password.getBytes();
            net_out.write(login_data);
            for (ii = login_data.length; ii < 64; ii++)
            net_out.writeByte(0);
            // 接收結果
            for (;;) {
                if (!read_nm()) break; // 等到return value了
            }
        } catch (IOException e) {
            shut_down();
            throw e;
        }
        return (Tools.ToInt(in_data, 0));
    }


    synchronized boolean GetNodeRuntimeInfo(int p1, int p2) throws IOException {
        byte[] param = new byte[2 * 4];

        if (client_s == null) throw new IOException();

        Tools.ToArray(out_header, 0, 4 + 2 * 4);
        Tools.ToArray(out_header, 4, NM_GetNodeRuntimeInfo);

        Tools.ToArray(param, 4 * 0, p1);
        Tools.ToArray(param, 4 * 1, p2); // Width
        try {
            net_out.write(out_header);
            net_out.write(param);
            // 接收結果
            for (;;) {
                if (!read_nm()) break; // 等到return value了
            }
        } catch (IOException e) {
            shut_down();
            throw e;
        }
        if (Tools.ToInt(in_data, 0) != 0) {
            int AlarmStatus;
            int VideoChannelCount;
            int TotalFileCount;

            AlarmStatus = Tools.ToInt(in_data, 4 + 0);
            VideoChannelCount = Tools.ToInt(in_data, 4 + 4);
            TotalFileCount = Tools.ToInt(in_data, 4 + 4 + 4 + 32 + 8 + 4 + 4 + 4 + 4);

            video_channel_count = VideoChannelCount;

            Log.d(DTag, "GetNodeRuntimeInfo( " + p1 + "," + p2 + " ): " + AlarmStatus + "," + VideoChannelCount + "," + TotalFileCount);
            return (true);
        } else return (false);
    }
	
	// MFormat_ADPCM
	synchronized boolean StartAudioChannel(int cn, int media_format) throws IOException {
        byte[] param = new byte[3 * 4];
		if(audio_recorder != null)
			audio_recorder.startRecording();

		if (client_s == null) {
    		Log.d(DTag, "Audio channel is off due to not connected...");
			throw new IOException();
		}
		Log.d(DTag, "StartAudioChannel...");
		
        Tools.ToArray(out_header, 0, 4 + 3 * 4);
        Tools.ToArray(out_header, 4, NM_StartAudioChannel);

        Tools.ToArray(param, 4 * 0, cn);
        Tools.ToArray(param, 4 * 1, media_format); // media format
        Tools.ToArray(param, 4 * 2, 0); // Reserved        

        try {
            net_out.write(out_header);
            net_out.write(param);
            // 接收結果
            for (;;) {
                if (!read_nm()) break; // 等到return value了
            }
        } catch (IOException e) {
            //shut_down();
    		Log.d(DTag, "Audio channel is off due to socket IO error");
            throw e;
        }
        if (Tools.ToInt(in_data, 4) != media_format) {
            Log.d(DTag, "StartAudioChannel( " + (cn + 1) + " )=ADPCM");
            return (true);
        } else {
    		Log.d(DTag, "Audio channel is off due to codec error...");
        	return (false);
        }
	}
	
    synchronized boolean StartChannel(int cn, int media_format) throws IOException {
        byte[] param = new byte[17 * 4];

        if (client_s == null) throw new IOException();

        image_w = 0;
        image_h = 0;

        Tools.ToArray(out_header, 0, 4 + 17 * 4);
        Tools.ToArray(out_header, 4, NM_StartChannel2);

        Tools.ToArray(param, 4 * 0, cn);
        Tools.ToArray(param, 4 * 1, 0); // Width
        Tools.ToArray(param, 4 * 2, 0); // Height        
        Tools.ToArray(param, 4 * 3, media_format);
        Tools.ToArray(param, 4 * 4, 0); // Handler
        Tools.ToArray(param, 4 * 5, 0); // Quality
        Tools.ToArray(param, 4 * 6, 0); // KFInterval
        Tools.ToArray(param, 4 * 7, 0); // Optimization
        Tools.ToArray(param, 4 * 8, 0); // Method

        Tools.ToArray(param, 4 * 9, 0); // FR1
        Tools.ToArray(param, 4 * 10, 0); // FR2	
        Tools.ToArray(param, 4 * 11, 0); // FR3	
        Tools.ToArray(param, 4 * 12, 0); // FR4
        Tools.ToArray(param, 4 * 13, 0); // FR5
        Tools.ToArray(param, 4 * 14, 0); // FR6
        Tools.ToArray(param, 4 * 15, 0); // FR7
        Tools.ToArray(param, 4 * 16, 0); // FR8

        try {
            net_out.write(out_header);
            net_out.write(param);
            // 接收結果
            for (;;) {
                if (!read_nm()) break; // 等到return value了
            }
        } catch (IOException e) {
            shut_down();
            throw e;
        }
		
		StartAudioChannel(cn, MFormat_ADPCM);
		
        if (Tools.ToInt(in_data, 0) != 0) {
            Resources res = getResources();
            // 若charset不支援，則會丟出：UnsupportedEncodingException的例外
            channel_name = new String(in_data, 4, Tools.Strlen(in_data, 4),
            res.getString(R.string.ApplicationCharset));
            channel_desc = new String(in_data, 4 + 64, Tools.Strlen(in_data, 4 + 64),
            res.getString(R.string.ApplicationCharset));

            Log.d(DTag, "StartChannel( " + (cn + 1) + " )=" + channel_name);
            return (true);
        } else return (false);
    }

    synchronized boolean StopAudioChannel(int cn) throws IOException {
        byte[] cn_data = new byte[4];

		if(audio_recorder != null)
			audio_recorder.stop();
		if (client_s == null) throw new IOException();

        Tools.ToArray(out_header, 0, 8);
        Tools.ToArray(out_header, 4, NM_StopAudioChannel);
        Tools.ToArray(cn_data, 0, cn);

        try {
            net_out.write(out_header);
            net_out.write(cn_data);
            // 接收結果
            for (;;) {
                if (!read_nm()) break; // 等到return value了
            }
        } catch (IOException e) {
            // shut_down();
            throw e;
        }
        if (in_data[0] != 0) {
            Log.d(DTag, "StopAudioChannel( " + (cn + 1) + " )");
            return (true);
        } else return (false);
    }

    synchronized boolean StopChannel(int cn) throws IOException {
        byte[] cn_data = new byte[4];

        if (client_s == null) throw new IOException();

        Tools.ToArray(out_header, 0, 8);
        Tools.ToArray(out_header, 4, NM_StopChannel);
        Tools.ToArray(cn_data, 0, cn);

        try {
            net_out.write(out_header);
            net_out.write(cn_data);
            // 接收結果
            for (;;) {
                if (!read_nm()) break; // 等到return value了
            }
        } catch (IOException e) {
            shut_down();
            throw e;
        }
		StopAudioChannel(cn);
        if (in_data[0] != 0) {
            Log.d(DTag, "StopChannel( " + (cn + 1) + " )");
            return (true);
        } else return (false);
    }

	// upload audio
	/*
	void VideoServerService::SendAudioSample( const AudioSampleData &as ) throw( NetworkError )
 
	struct AudioSampleData {
	  MediaFormat Format;    // 若為MF_Unknown則表示此物件不可使用！
	  UDWord CodecType;    // 目前都是填0
	  UDWord Status;    // 旗標：[0:11]、channel number：[12:23]、音量：[24:31]     ----------> 填0沒關係
	  PortableTime Time;    // Audio sample data的時間
	  int BytesUsed;
	  UByte ASData[MaxASDataSize];
		  // 可最佳化！先固定配置（使用管理程式）一固定額度然後...
		  // 經常用在網路封包上...
	};
 
	class PortableTime {
	public:
	  DWord Year;    // Years since 1900
	  DWord Mon;    // [0, 11]
	  DWord Day;    // [1, 31]
	  DWord Hour;    // [0, 23]
	  DWord Min;    // [0, 59]
	  DWord Sec;    // [0, 59]
	  DWord MilliSec;    // [0, 999]
	  DWord WDay;    // Days since Sunday - [0, 6]
	};
	*/
	
    // Read Network Message
    // 若執行時偵測到網路錯誤會清除所有資源！
    // 執行"服務分配"，只讓run()呼叫
    private synchronized boolean ReadNM() throws IOException {
        if (client_s == null) throw new IOException();

        if (net_in.available() <= 0) // 沒封包可以讀取！
        return (false);
        read_nm(); // 可以讓它丟出例外...
        return (true);
    }


    // 內部共用的讀取封包函數
    // 傳回 true：表示封包已經處理完成
    //     false:表示封包未處理完（因為是return value）
    private boolean read_nm() throws IOException {
        int rps, message_id;

        if (client_s == null) throw new IOException();

        try {
            net_in.readFully(in_header);
            rps = Tools.ToInt(in_header, 0);
            if (rps > 4) {
                if (rps - 4 > in_data.length) // 不大可能！
                in_data = new byte[rps - 4];
                net_in.readFully(in_data, 0, rps - 4);
            }
        } catch (IOException e) {
            shut_down();
            throw e;
        }
        // 分配網路服務...
        message_id = Tools.ToInt(in_header, 4);
        switch (message_id) {
            case NM_ReturnValue:
                return (false);
            case NM_SendVideoFrame:
                S_SendVideoFrame(in_data);
                break;
			case NM_SendAudioSample:
	    		//Log.d(DTag, "got Audio sample");
				long start = System.currentTimeMillis();
				int data_len = Tools.ToInt(in_data, 44);
				S_SendAudioFrame(in_data, data_len + 48);
				long end = System.currentTimeMillis();
				Log.d(DTag, "get len="+data_len+"bytes, Execution time was "+(end-start)+" ms.");
				// view_desktop.SetMessage("len="+data_len+"bytes, Execution time was "+(end-start)+" ms.");
				break;
            case NM_CheckAlive:
                // 目前server不會主動送check alive封包來...
                break;
            default:
                Log.d(DTag, "Network message: " + message_id + " not defined!");
                shut_down();
                throw new IOException();
        }
        return (true);
    }

    // 不檢查MediaFormat等欄位...
    private synchronized void S_SendAudioFrame(byte[] data, int size) {
		int audio_codec = Tools.ToInt(data, 0);
        if (audio_codec != MFormat_ADPCM) {
			Log.d(DTag, "can not decode audio format = " + audio_codec);
			return;
        }
		aud_player.put(data, size);
	}

    // 不檢查MediaFormat等欄位...
    private synchronized void S_SendVideoFrame(byte[] data) {
        if (image_w != Tools.ToInt(data, 0) || image_h != Tools.ToInt(data, 4)) {
            image_w = Tools.ToInt(data, 0);
            image_h = Tools.ToInt(data, 4);
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 4 * 5 + 32 + 4 * 5 + 4, Tools.ToInt(data, 4 * 5 + 32 + 4 * 5));
        if (bitmap == null) {
            Log.d(DTag, "JPEG decode failed!");
            return;
        }
        DrawImage(channel_n, image_w, image_h, channel_name, bitmap);
    }

    // 發生在網路突然產生錯誤（由於連線狀態不預期地中斷），將清除網路 資源，重置變數...
    private void shut_down() {
        try {
            if (client_s != null) client_s.close();
        } catch (IOException except) {}
        reset_data();
    }
}

class LiveAudioPlayer implements Runnable{
    private static final String DTag = "LAP";
	private LiveVideoSurfaceView viewer = null;
	private List<short []> samples = new ArrayList<short []>();
	private boolean running_flag = true;
	int audio_bufsize;
	AudioTrack audio_track_player = null;

    public native void testAdpcm();
    public native int decodeAdpcm(byte[] src, int idx, short[] dest);
	static {
		System.loadLibrary("adpcm");
	}
	
	public LiveAudioPlayer(LiveVideoSurfaceView v)
	{
        Log.d(DTag, "try to call JNI()");
        testAdpcm();
        Log.d(DTag, "called JNI end");
		viewer = v;
	}
	
    public void run() {
		Log.d(DTag, "init AudioTrack...");
		audio_bufsize = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
		audio_track_player = new AudioTrack(AudioManager.STREAM_MUSIC, 8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, audio_bufsize, AudioTrack.MODE_STREAM);
		audio_track_player.play();
		while(running_flag)
		{
			short[] data = null;
			synchronized(samples) {
				if(samples.size() > 0)
				{
					// audio data length Tools.ToInt(data, 44)
					data = samples.get(0);
					samples.remove(0);
				}
			}
			if(data != null)
			{
				int data_len = data.length;
				if(data_len > 0)
					audio_track_player.write(data, 0, data_len);				
				data = null;
			}
			else
			{
				try {
					Thread.sleep(1000 / 20); // 依然釋放CPU資源...
				} catch (InterruptedException e) {}
			}
		}
		Log.d(DTag, "exit AudioTrack...");
		audio_track_player.stop();
		audio_track_player.release();	
    }
    public void setRunning(boolean flag) {
		running_flag = flag;
	}
    private byte sequrence = 0;
    public void put(byte[] sample, int size) {
    	++sequrence;
        synchronized(samples) {
			if(samples.size() <= 200)
			{
				short[] out_audio_buffer = new short[8000];
				//samples.add(sample.clone());
				long start = System.currentTimeMillis();
				//int data_len = AudioCodec.DecodeADPCM(sample, 48, out_audio_buffer, 8000);
				int data_len = decodeAdpcm(sample, 48, out_audio_buffer);
				data_len /= 2;
				long end = System.currentTimeMillis();
				Log.d(DTag, "decode len="+data_len+"bytes, Execution time was "+(end-start)+" ms.");
				if(data_len > 0)
				{
					samples.add(Arrays.copyOfRange(out_audio_buffer, 0, data_len));
				}
			}
			else
			{
				Log.d(DTag, "drop audio "+ sequrence+"\n");
			}
        }
    }
}
/*---------------------------------------------------------+
|         Class: LiveVideoSurfaceView                      |
+---------------------------------------------------------*/

class LiveVideoSurfaceView extends SurfaceView implements Callback2 {
    private static final String DTag = "LVSV";
    private SurfaceHolder s_holder;
    private String last_output_text = new String();
	private String message = new String();

    public LiveVideoSurfaceView(Context context) {
        super(context);
        s_holder = getHolder();
        s_holder.addCallback(this);

        //    setFocusable(true); //使用Key Event，setFocusable設成true，可以聚焦
        //    requestFocus(); //要求聚焦，沒有聚焦的話，會收不到正確的Key Event
    }

    // 不檢查MediaFormat等欄位...
    public void DrawImage(int channel_index, int width, int height, String channel_name, Bitmap bitmap) {
        final int text_size = 32;
        final int text_start_x = 32, text_start_y = 32;
        Canvas canvas = s_holder.lockCanvas();

        if (canvas != null) {
            int scale_w, scale_h;

            scale_w = bitmap.getWidth();
            if (Tools.NeedDoubleHeight(bitmap.getWidth(), bitmap.getHeight())) {
                scale_h = bitmap.getHeight() * 2;
            } else {
                scale_h = bitmap.getHeight();
            }

            GBlock block = new GBlock();

            Tools.FitBlock(canvas.getWidth(), canvas.getHeight(), scale_w, scale_h, 0, 0, block);

            Rect dest_rect = new Rect();

            dest_rect.left = block.X;
            dest_rect.top = block.Y;
            dest_rect.right = block.X + block.Width;
            dest_rect.bottom = block.Y + block.Height;

            Paint paint = new Paint();
            Rect bounds = new Rect();
            String output_text = (channel_index + 1) + "  " + channel_name + message;

            paint.setColor(Color.GREEN);
            paint.setTextSize(text_size);
            paint.setAntiAlias(true);
            paint.setDither(true);
            paint.getTextBounds(output_text, 0, output_text.length(), bounds);

            canvas.drawBitmap(bitmap, new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()), dest_rect, paint);
            canvas.drawText(output_text,
            block.X + text_start_x - bounds.left, block.Y + text_start_y - bounds.top, paint);

            getHolder().unlockCanvasAndPost(canvas);
        }
    }

    public void SetMessage(String message_text) {
        synchronized(message) {
            message = message_text;
        }
    }

    public void DrawText(String output_text) {
        synchronized(last_output_text) {
            last_output_text = output_text;
        }
        if (output_text.length() > 0) {
            inner_draw_text(output_text);
        }
    }

    public void inner_draw_text(String output_text) {
        final int text_size = 32;
        Canvas canvas = s_holder.lockCanvas();

        if (canvas != null) {
            canvas.drawColor(Color.BLACK); // 在沒有設定顏色的狀況下，預設是黑色...
            /*
      {
      Drawable bg_color;
          
        bg_color=getBackground();    // 會傳回null
        bg_color.draw( canvas );
      }
*/

            Paint paint = new Paint();
            Rect bounds = new Rect();

            paint.setColor(Color.GREEN);
            paint.setTextSize(text_size);
            paint.setAntiAlias(true);
            paint.setDither(true);
            paint.getTextBounds(output_text, 0, output_text.length(), bounds);

            /*
預設的Paint.Align是：LEFT

預設的Paint.FontMetrics：（當font size設定為32時）
  ascent	-29.703125	
  bottom	8.671875	
  descent	7.546875	
  leading	0.0	
  top	-33.53125
*/
            canvas.drawText(output_text, (canvas.getWidth() - bounds.width()) / 2 - bounds.left, (canvas.getHeight() - bounds.height()) / 2 - bounds.top, paint);

            getHolder().unlockCanvasAndPost(canvas);
        }
    }


    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(DTag, "surfaceChanged()");
    }
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(DTag, "surfaceCreated()");
    }
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(DTag, "surfaceDestroyed()");
    }
    public void surfaceRedrawNeeded(SurfaceHolder holder) {
        String temp_text;

        Log.d(DTag, "surfaceRedrawNeeded()");
        synchronized(last_output_text) {
            temp_text = last_output_text;
        }
        if (temp_text.length() > 0) {
            inner_draw_text(temp_text);
        }
    }

    // 按下鍵時的處理
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d("TEST", "onKeyDown");
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                break;
            default:
                return false;
        }
        return true;
    }
    //提起鍵時的處理
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        Log.d("TEST", "onKeyDown");
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                break;
            default:
                return false;
        }
        return true;
    }
}


/*---------------------------------------------------------+
|         Class: Tools                                     |
+---------------------------------------------------------*/

class GBlock {
    int X = 0, Y = 0;
    int Width = 0, Height = 0;
};


class AddressInfo {
    String IPAddress = new String();
    int PortN = 0;
};

