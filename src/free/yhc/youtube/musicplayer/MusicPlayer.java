package free.yhc.youtube.musicplayer;

import static free.yhc.youtube.musicplayer.model.Utils.eAssert;
import static free.yhc.youtube.musicplayer.model.Utils.logD;
import static free.yhc.youtube.musicplayer.model.Utils.logW;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;

import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.AsyncTask;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import free.yhc.youtube.musicplayer.model.DB;
import free.yhc.youtube.musicplayer.model.DB.ColMusic;
import free.yhc.youtube.musicplayer.model.Err;
import free.yhc.youtube.musicplayer.model.UiUtils;
import free.yhc.youtube.musicplayer.model.Utils;

public class MusicPlayer implements
MediaPlayer.OnBufferingUpdateListener,
MediaPlayer.OnCompletionListener,
MediaPlayer.OnPreparedListener,
MediaPlayer.OnErrorListener,
MediaPlayer.OnInfoListener,
MediaPlayer.OnVideoSizeChangedListener,
MediaPlayer.OnSeekCompleteListener {
    private static final String WLTAG = "MusicPlayer";
    private static final int    RETRY_ON_ERROR  = 1;
    private static final int    NO_BUFFERING    = -1;
    private static final int    RETRY_HANG_ON_BUFFERING     = 4;
    private static final int    TIMEOUT_HANG_ON_BUFFERING   = 5 * 1000;

    private static final Comparator<NrElem> sNrElemComparator = new Comparator<NrElem>() {
        @Override
        public int compare(NrElem o1, NrElem o2) {
            if (o1.n > o2.n)
                return 1;
            else if (o1.n < o2.n)
                return -1;
            else
                return 0;
        }
    };
    private static final Comparator<Music> sMusicTitleComparator = new Comparator<Music>() {
        @Override
        public int compare(Music o1, Music o2) {
            return o1.title.compareTo(o2.title);
        }
    };

    private static MusicPlayer instance = null;


    private final Resources     mRes        = Utils.getAppContext().getResources();
    private final DB            mDb         = DB.get();

    // ------------------------------------------------------------------------
    // Runnables
    // ------------------------------------------------------------------------
    private final UpdateProgress        mUpdateProg             = new UpdateProgress();
    private final RetryHangOnBuffering  mRetryHangOnBuffering   = new RetryHangOnBuffering();

    private WakeLock            mWl          = null;
    private WifiLock            mWfl         = null;

    private MediaPlayer         mMp         = null;
    private State               mMpS        = State.INVALID; // state of mMp;

    // ------------------------------------------------------------------------
    // UI Control.
    // ------------------------------------------------------------------------
    private Context             mVContext   = null;
    private View                mPlayerv    = null;
    private ProgressBar         mProgbar    = null;

    // ------------------------------------------------------------------------
    // MediaPlayer Runtime Status
    // ------------------------------------------------------------------------
    private int                 mBuffering  = NO_BUFFERING;
    // This variable is for retrying errored music again.
    // Sometimes, playing music fails with error just after start playing due to unknown several issues.
    // (May be due to mediaplayer bug or unstable device platform.)
    // In this case trying to recover this is better than just skip it.
    // This also a kind of workaround and dirty hack!
    private int                 mPlayErrRetry = RETRY_ON_ERROR;

    private Music[]             mMusics     = null;
    private int                 mMusici     = -1;

    // see "http://developer.android.com/reference/android/media/MediaPlayer.html"
    public enum State {
        INVALID,
        IDLE,
        INITIALIZED,
        PREPARING,
        PREPARED,
        STARTED,
        STOPPED,
        PAUSED,
        PLAYBACK_COMPLETED,
        END,
        ERROR
    }

    public static class Music {
        public String   title;
        public Uri      uri;
        public Music(Uri aUri, String aTitle) {
            uri = aUri;
            title = aTitle;
        }
    }

    private static class NrElem {
        public int      n;
        public Object   tag;
        NrElem(int aN, Object aTag) {
            n = aN;
            tag = aTag;
        }
    }

    private class UpdateProgress implements Runnable {
        private ProgressBar progbar = null;
        private int         lastProgress = -1;

        void start(ProgressBar aProgbar) {
            end();
            progbar = aProgbar;
            lastProgress = -1;
            run();
        }

        void end() {
            progbar = null;
            lastProgress = -1;
            Utils.getUiHandler().removeCallbacks(this);
        }

        @Override
        public void run() {
            if (null != progbar && progbar == mProgbar) {
                int curProgress = mpGetCurrentPosition() * 100 / mpGetDuration();
                if (curProgress > lastProgress) {
                    mProgbar.setProgress(curProgress);

                    // This is dirty hack code for unexpected buffering - workaround of media player...
                    // DO NOT think about the reason! This is guard code to avoid strange bug of media player
                    //   based on experimental test.
                    mRetryHangOnBuffering.end();
                }
                lastProgress = curProgress;
                Utils.getUiHandler().postDelayed(this, 1000);
            }
        }
    }

    // This RetryHangOnBuffering class is for workaround regarding bugs of MediaPlayer.
    // So, module is very hacky and dirty.
    // But... sigh... yes, but, I can't help .. :-(
    // DO NOT think about the reason! This is guard code to avoid strange bug of media player
    //   based on experimental test.
    private class RetryHangOnBuffering implements Runnable {
        private int         retry       = 0;
        private MediaPlayer mp          = null;

        void start(MediaPlayer aMp) {
            logD("MPlayer : HangOnBuffering - start");
            end();
            retry = RETRY_HANG_ON_BUFFERING;
            mp = aMp;
            Utils.getUiHandler().postDelayed(this, TIMEOUT_HANG_ON_BUFFERING);
        }

        void end() {
            logD("MPlayer : HangOnBuffering - end");
            mp = null;
            Utils.getUiHandler().removeCallbacks(this);
            retry = 0;
        }

        boolean isActive() {
            return null != mp && retry > 0;
        }

        @Override
        public void run() {
            if (null == mp || mp != mpGet()) {
                logW("MPlayer : RetryHangOnRecover - unexpected MediaPlayer!");
                return; // unexpected media player... skip this case...
            }

            if (0 != mp.getCurrentPosition()) {
                // Hang on buffering in the middle of playing.
                // If we try to recover it, this means play one music over and over again.
                // So, just skip this music if it fails in the middle of playing.
                logD("MPlayer : Buffering fails in the middle of music - skip this music!");
                retry = 0;
            }

            if (retry-- > 0) {
                if (!isMediaPlayerAvailable()) {
                    end();
                    return;
                }

                logD("MusicPlayer : try to recover from HangOnBuffering ***");

                // This should handle the case only hang on first buffering...
                // This is dirty but workaround based on experimental...
                // Check it still preparing for recovery.
                if (State.PREPARING != mpGetState()) {
                    // Below code is fully hack of MusicPlayer's mechanism to recover from exceptional state.

                    // save current state of this module.
                    int retrysv = retry;

                    // fully restart music player from current music.
                    startMusicPlayerAsync();
                    // Now, state of this module is fully changed from before 'startMusicPlayerAysnc()' is called.
                    // So, state should be recovered properly.
                    retry = retrysv;


                    // NOTE!
                    // mpStop() should not be used because mpStop() leads to onStateChanged().
                    // But, this is exceptional retry.
                    // So, onStateChanged() should not be called for intermediate state (STOPPED).
                    // At STOPPED state, mRetryHangOnBuffering.end() is called.
                    // And this should be avoided!
                }

                Utils.getUiHandler().postDelayed(this, TIMEOUT_HANG_ON_BUFFERING);
            }

            if (retry < 0) {
                logD("MPlayer : HangOnBuffering - recovery fails.");
                // Buffering fails.
                // So, let's finish this music and complete it - hacking...
                // Let's move to next music!!
                MusicPlayer.this.onCompletion(mpGet());
            }
        }
    }

    private MusicPlayer() {
    }

    @Override
    protected void
    finalize() {
        mMp.release();
    }

    private void
    acquireLocks() {
        eAssert(null == mWl && null == mWfl);
        mWl = ((PowerManager)Utils.getAppContext().getSystemService(Context.POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WLTAG);
        // Playing youtube requires high performance wifi for high quality media play.
        mWfl = ((WifiManager)Utils.getAppContext().getSystemService(Context.WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WLTAG);
        mWl.acquire();
        mWfl.acquire();
    }

    private void
    releaseLocks() {
        if (null != mWl)
                mWl.release();

        if (null != mWfl)
            mWfl.release();

        mWl = null;
        mWfl = null;
    }

    // ========================================================================
    //
    // Media Player Interfaces
    //
    // ========================================================================
    private boolean
    isMediaPlayerAvailable() {
        return null != mMp
               && State.END != mpGetState()
               && State.INVALID != mpGetState();
    }
    private void
    mpSetState(State newState) {
        logD("MusicPlayer : State : " + mMpS.name() + " => " + newState.name());
        State old = mMpS;
        mMpS = newState;
        onMpStateChanged(old, newState);
    }

    private State
    mpGetState() {
        return mMpS;
    }

    private void
    initMediaPlayer(MediaPlayer mp) {
        mp.setOnBufferingUpdateListener(this);
        mp.setOnCompletionListener(this);
        mp.setOnPreparedListener(this);
        mp.setOnVideoSizeChangedListener(this);
        mp.setOnSeekCompleteListener(this);
        mp.setOnErrorListener(this);
        mp.setOnInfoListener(this);
        mp.setScreenOnWhilePlaying(false);
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
    }

    private void
    mpNewInstance() {
        mMp = new MediaPlayer();
        mpSetState(State.INVALID);
        initMediaPlayer(mMp);
    }

    private MediaPlayer
    mpGet() {
        return mMp;
    }

    private void
    mpSetDataSource(Uri uri) throws IOException {
        mMp.setDataSource(Utils.getAppContext(), uri);
        mpSetState(State.INITIALIZED);
    }

    private void
    mpPrepareAsync() {
        mpSetState(State.PREPARING);
        mMp.prepareAsync();
    }

    private void
    mpRelease() {
        if (null == mMp || State.END == mpGetState())
            return;


        if (State.ERROR != mMpS && mMp.isPlaying())
            mMp.stop();

        mMp.release();
        mpSetState(State.END);
    }

    private void
    mpReset() {
        mMp.reset();
        mpSetState(State.IDLE);
    }

    public int
    mpGetCurrentPosition() {
        switch (mpGetState()) {
        case ERROR:
        case END:
            return 0;
        }
        return mMp.getCurrentPosition();
    }

    public int
    mpGetDuration() {
        switch(mpGetState()) {
        case PREPARED:
        case STARTED:
        case PAUSED:
        case STOPPED:
        case PLAYBACK_COMPLETED:
            return mMp.getDuration();
        }
        return 0;
    }

    public boolean
    mpIsPlaying() {
        //logD("MPlayer - isPlaying");
        return mMp.isPlaying();
    }

    public void
    mpPause() {
        logD("MPlayer - pause");
        mMp.pause();
        mpSetState(State.PAUSED);
    }

    public void
    mpSeekTo(int pos) {
        logD("MPlayer - seekTo");
        mMp.seekTo(pos);
    }

    public void
    mpStart() {
        logD("MPlayer - start");
        mMp.start();
        mpSetState(State.STARTED);
    }

    public void
    mpStop() {
        logD("MPlayer - stop");
        mMp.stop();
    }

    // ========================================================================
    //
    // General Control
    //
    // ========================================================================
    private void
    onMpStateChanged(State from, State to) {
        if (from == to)
            return;

        configurePlayerViewAll();
        switch (mpGetState()) {
        case STOPPED:
        case PLAYBACK_COMPLETED:
        case IDLE:
        case END:
        case ERROR:
            mRetryHangOnBuffering.end();
        }
    }


    private void
    playMusicAsync() {
        mPlayErrRetry = RETRY_ON_ERROR;
        while (mMusici < mMusics.length) {
            try {
                // onPrepare
                mpSetDataSource(mMusics[mMusici].uri);
            } catch (IOException e) {
                mMusici++;
                continue;
            }
            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    int n = mDb.updateMusic(ColMusic.URL, mMusics[mMusici].uri.toString(),
                                    ColMusic.TIME_PLAYED, System.currentTimeMillis());
                    logD("MusicPlayer : TIME_PLAYED updated : " + n);
                }
            });
            mpPrepareAsync();
            return;
        }

        Utils.getUiHandler().post(new Runnable() {
            @Override
            public void run() {
                playDone();
            }
        });
    }

    private void
    startMusicPlayerAsync() {
        mpRelease();
        mpNewInstance();
        mpReset();

        // Reset all mMp related runtime variable.
        mBuffering  = NO_BUFFERING;
        mRetryHangOnBuffering.end();

        playMusicAsync();
    }

    private void
    playDone() {
        logD("MPlayer - playDone");
        setPlayerViewTitle(mRes.getText(R.string.msg_playing_done));
        releaseLocks();
    }

    // ========================================================================
    //
    // Player View Handling
    //
    // ========================================================================

    /**
     *
     * @param title
     * @param buffering
     *   buffering percent. '-1' means no-bufferring.
     */
    private void
    setPlayerViewTitle(CharSequence title, int buffering) {
        if (null == mPlayerv || null == title)
            return;

        TextView tv = (TextView)mPlayerv.findViewById(R.id.title);
        if (buffering < 0)
            tv.setText(title);
        else
            tv.setText("(" + mRes.getText(R.string.buffering) + "-" + buffering + "%) " + title);
    }

    private void
    setPlayerViewTitle(CharSequence title) {
        setPlayerViewTitle(title, -1);
    }


    private void
    setPlayerViewPlayButton(int icon, boolean clickable) {
        if (null == mPlayerv)
            return;

        ImageView play = (ImageView)mPlayerv.findViewById(R.id.play);
        play.setImageResource(icon);
        play.setClickable(clickable);
    }

    private void
    configurePlayerViewTitle() {
        if (null == mPlayerv)
            return;

        CharSequence musicTitle = null;
        if (null != mMusics
                && 0 <= mMusici
                && mMusici < mMusics.length) {
            musicTitle = mMusics[mMusici].title;
        }

        switch (mpGetState()) {
        case ERROR:
            setPlayerViewTitle(mRes.getText(R.string.msg_mplayer_err_unknown));
            break;

        case PAUSED:
        case STARTED:
            eAssert(null != musicTitle);
            if (null != musicTitle)
                setPlayerViewTitle(musicTitle, mBuffering);
            break;

        default:
            setPlayerViewTitle(mRes.getText(R.string.msg_preparing_mplayer));
        }
    }

    private void
    configurePlayerViewButton() {
        if (null == mPlayerv)
            return;

        switch (mpGetState()) {
        case PAUSED:
            setPlayerViewPlayButton(R.drawable.ic_media_play, true);
            break;

        case STARTED:
            setPlayerViewPlayButton(R.drawable.ic_media_pause, true);
            break;

        default:
            setPlayerViewPlayButton(R.drawable.ic_block, false);
        }
    }

    private void
    configurePlayerViewProgressBar() {
        if (null == mProgbar)
            return;

        switch (mpGetState()) {
        case STARTED:
            mUpdateProg.start(mProgbar);
            break;

        case PAUSED:
            mUpdateProg.end();
            break;

        default:
            mUpdateProg.end();
            mProgbar.setProgress(0);
        }
    }

    private void
    configurePlayerViewAll() {
        configurePlayerViewTitle();
        configurePlayerViewButton();
        configurePlayerViewProgressBar();
    }


    private void
    initPlayerView() {
        ImageView play = (ImageView)mPlayerv.findViewById(R.id.play);
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switch (mpGetState()) {
                case STARTED:
                    mpPause();
                    break;

                case PAUSED:
                    mpStart();
                    break;

                default:
                    if (null != mVContext)
                        UiUtils.showTextToast(mVContext, R.string.msg_mplayer_err_not_allowed);
                }
            }
        });
        mProgbar.setTag(false); // tag value means "keep progress or not."
        configurePlayerViewAll();
    }
    // ========================================================================
    //
    // Public interface
    //
    // ========================================================================
    public static MusicPlayer
    get() {
        if (null == instance)
            instance = new MusicPlayer();
        return instance;
    }

    public Err
    init() {
        return Err.NO_ERR;
    }

    public Err
    setController(Context context, View playerv) {
        if (context == mVContext && mPlayerv == playerv)
            // controller is already set for this context.
            // So, nothing to do. just return!
            return Err.NO_ERR;

        mVContext = context;
        mPlayerv = playerv;
        mProgbar = null;

        if (null == mPlayerv)
            return Err.NO_ERR;

        eAssert(null != mPlayerv.findViewById(R.id.music_player_layout_magic_id));
        mProgbar = (ProgressBar)mPlayerv.findViewById(R.id.progressbar);
        initPlayerView();

        return Err.NO_ERR;
    }

    public void
    unsetController(Context context) {
        if (null != mVContext && context != mVContext)
            logW("MusicPlayer : Unset Controller at different context...");

        mProgbar = null;
        mPlayerv = null;
        mVContext = null;

    }

    /**
     * Should be called after {@link #setController(Context, View)}
     * @param ms
     */
    public void
    startMusicsAsync(Music[] ms) {
        eAssert(Utils.isUiThread());
        eAssert(null != mPlayerv);

        releaseLocks();
        acquireLocks();

        mMusics = ms;
        mMusici = 0;

        startMusicPlayerAsync();
    }



    /**
     * This may takes some time. it depends on size of cursor.
     * @param c
     *   Musics from this cursor will be sorted order by it's title.
     *   So, this function doesn't require ordered cursor.
     *   <This is for performance reason!>
     * @param coliTitle
     * @param coliUrl
     * @param shuffle
     * @return
     */
    private Music[]
    getMusics(Cursor c, int coliTitle, int coliUrl, boolean shuffle) {
        if (!c.moveToFirst())
            return new Music[0];

        Music[] ms = new Music[c.getCount()];
        int i = 0;
        if (!shuffle) {
            do {
                ms[i++] = new Music(Uri.parse(c.getString(coliUrl)),
                                    c.getString(coliTitle));
            } while (c.moveToNext());
            Arrays.sort(ms, sMusicTitleComparator);
        } else {
            // This is shuffled case!
            Random r = new Random(System.currentTimeMillis());
            NrElem[] nes = new NrElem[c.getCount()];
            do {
                nes[i++] = new NrElem(r.nextInt(),
                                      new MusicPlayer.Music(Uri.parse(c.getString(coliUrl)),
                                                                      c.getString(coliTitle)));
            } while (c.moveToNext());
            Arrays.sort(nes, sNrElemComparator);
            for (i = 0; i < nes.length; i++)
                ms[i] = (MusicPlayer.Music)nes[i].tag;
        }
        return ms;
    }

    /**
     *
     * @param c
     *   closing cursor is in charge of this function.
     * @param coliUrl
     * @param coliTitle
     * @param shuffle
     */
    public void
    startMusicsAsync(final Cursor c, final int coliUrl, final int coliTitle, final boolean shuffle) {
        eAssert(Utils.isUiThread());
        eAssert(null != mPlayerv);

        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                final Music[] ms = getMusics(c, coliTitle, coliUrl, shuffle);
                Utils.getUiHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        startMusicsAsync(ms);
                    }
                });
                c.close();
            }
        });
    }

    public boolean
    isMusicPlaying() {
        return null != mMusics && mMusici < mMusics.length;
    }

    // ============================================================================
    //
    //
    //
    // ============================================================================



    // ============================================================================
    //
    // Override for "MediaPlayer.*"
    //
    // ============================================================================
    @Override
    public void
    onBufferingUpdate (MediaPlayer mp, int percent) {
        logD("MPlayer - onBufferingUpdate");
        if (mRetryHangOnBuffering.isActive())
            mRetryHangOnBuffering.end();
        mBuffering = percent;
        configurePlayerViewTitle();
    }

    @Override
    public void
    onCompletion(MediaPlayer mp) {
        logD("MPlayer - onCompletion");
        mpSetState(State.PLAYBACK_COMPLETED);
        mMusici++;
        mpReset();
        playMusicAsync();
    }

    @Override
    public void
    onPrepared(MediaPlayer mp) {
        logD("MPlayer - onPrepared");
        mpSetState(State.PREPARED);
        mpStart(); // auto start
    }

    @Override
    public void
    onVideoSizeChanged(MediaPlayer mp, int width, int height) {
        logD("MPlayer - onVideoSizeChanged");
    }

    @Override
    public void
    onSeekComplete(MediaPlayer mp) {
        logD("MPlayer - onSeekComplete");
    }

    @Override
    public boolean
    onError(MediaPlayer mp, int what, int extra) {
        boolean tryAgain = false;
        mpSetState(State.ERROR);
        switch (what) {
        case MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK:
            logD("MPlayer - onError : NOT_VALID_FOR_PROGRESSIVE_PLAYBACK");
            break;

        case MediaPlayer.MEDIA_ERROR_SERVER_DIED:
            logD("MPlayer - onError : MEDIA_ERROR_SERVER_DIED");
            tryAgain = true;
            break;

        case MediaPlayer.MEDIA_ERROR_UNKNOWN:
            logD("MPlayer - onError : UNKNOWN");
            break;

        default:
            logD("MPlayer - onError");
        }

        if (!tryAgain || mPlayErrRetry-- <= 0)
            // Kind of HACK!
            // Retry is over.
            // Move to next music.
            mMusici++;

        // At startMusicPlayerAsync, mPlayErrRetry value is initialized.
        // This leads to infinite retrying.
        // So avoid this, backup current value and restore it after startMusicPlayerAsync() is called.
        int playErrRetrySv = mPlayErrRetry;

        // To recover from error state
        // Release -> Create new instance is essential.
        startMusicPlayerAsync();

        // Restore to saved value.
        mPlayErrRetry = playErrRetrySv;

        // onComplete() will be called.
        // So, playing next music will be done at 'onComplete()'

        return true; // DO NOT call onComplete Listener.
    }

    @Override
    public boolean
    onInfo(MediaPlayer mp, int what, int extra) {
        logD("MPlayer - onInfo");
        switch (what) {
        case MediaPlayer.MEDIA_INFO_VIDEO_TRACK_LAGGING:
            break;

        case MediaPlayer.MEDIA_INFO_NOT_SEEKABLE:
            break;

        case MediaPlayer.MEDIA_INFO_METADATA_UPDATE:
            break;

        case MediaPlayer.MEDIA_INFO_BUFFERING_START:
            mBuffering = 0;
            configurePlayerViewTitle();
            // NOTE
            // WHY below code is required?
            // Sometimes, mediaplayer is hung on starting buffering at some devices.
            // In this case we need to retry to recover this unexpected state.
            mRetryHangOnBuffering.start(mpGet());
            break;

        case MediaPlayer.MEDIA_INFO_BUFFERING_END:
            mBuffering = NO_BUFFERING;
            configurePlayerViewTitle();
            mRetryHangOnBuffering.end();
            break;

        case MediaPlayer.MEDIA_INFO_BAD_INTERLEAVING:
            break;

        case MediaPlayer.MEDIA_INFO_UNKNOWN:
            break;

        default:
        }
        return false;
    }
}