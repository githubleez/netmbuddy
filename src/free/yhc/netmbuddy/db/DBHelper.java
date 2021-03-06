/******************************************************************************
 * Copyright (C) 2012, 2013, 2014
 * Younghyung Cho. <yhcting77@gmail.com>
 * All rights reserved.
 *
 * This file is part of NetMBuddy
 *
 * This program is licensed under the FreeBSD license
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation
 * are those of the authors and should not be interpreted as representing
 * official policies, either expressed or implied, of the FreeBSD Project.
 *****************************************************************************/

package free.yhc.netmbuddy.db;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import free.yhc.netmbuddy.model.YTVideoFeed;
import free.yhc.netmbuddy.utils.Utils;

public class DBHelper {
    private static final boolean DBG = false;
    private static final Utils.Logger P = new Utils.Logger(DBHelper.class);

    private static final int MSG_WHAT_CLOSE         = 0;
    private static final int MSG_WHAT_CHECK_EXIST   = 1;

    private BGHandler               mBgHandler  = null;
    private CheckDupDoneReceiver    mDupRcvr    = null;

    public interface CheckDupDoneReceiver {
        void checkDupDone(DBHelper helper, CheckDupArg arg,
                          boolean[] results, Err err);
    }

    public static enum Err {
        NO_ERR
    }

    public static class CheckDupArg {
        public final Object                 tag;
        public final YTVideoFeed.Entry[]    ents;
        public CheckDupArg(Object aTag, YTVideoFeed.Entry[] aEnts) {
            tag = aTag;
            ents = aEnts;
        }
    }

    private static class BGThread extends HandlerThread {
        BGThread() {
            super("DBHelper.BGThread",Process.THREAD_PRIORITY_BACKGROUND);
        }

        @Override
        protected void
        onLooperPrepared() {
            super.onLooperPrepared();
        }
    }

    private static class BGHandler extends Handler {
        private final DBHelper  _mHelper;

        private boolean         _mClosed  = false;

        BGHandler(Looper    looper,
                  DBHelper  helper) {
            super(looper);
            _mHelper = helper;
        }

        private boolean[]
        checkDup(YTVideoFeed.Entry[] entries) {
            // TODO
            // Should I check "entries[i].available" flag???
            boolean[] r = new boolean[entries.length];
            for (int i = 0; i < r.length; i++)
                r[i] = DB.get().containsVideo(entries[i].media.videoId);
            return r;
        }

        private void
        sendCheckDupDone(final CheckDupArg arg, final boolean[] results, final Err err) {
            Utils.getUiHandler().post(new Runnable() {
                @Override
                public void
                run() {
                    CheckDupDoneReceiver rcvr = _mHelper.getCheckDupDoneReceiver();
                    if (!_mClosed && null != rcvr)
                        rcvr.checkDupDone(_mHelper, arg, results, err);
                }
            });
            return;
        }

        private void
        handleCheckDup(CheckDupArg arg) {
            sendCheckDupDone(arg, checkDup(arg.ents), Err.NO_ERR);
        }

        void
        close() {
            removeMessages(MSG_WHAT_CHECK_EXIST);
            sendEmptyMessage(MSG_WHAT_CLOSE);
        }

        @Override
        public void
        handleMessage(Message msg) {
            if (_mClosed)
                return;

            switch (msg.what) {
            case MSG_WHAT_CLOSE:
                _mClosed = true;
                ((HandlerThread)getLooper().getThread()).quit();
                break;

            case MSG_WHAT_CHECK_EXIST:
                handleCheckDup((CheckDupArg)msg.obj);
                break;
            }
        }
    }

    CheckDupDoneReceiver
    getCheckDupDoneReceiver() {
        return mDupRcvr;
    }

    // ======================================================================
    //
    //
    //
    // ======================================================================
    public void
    setCheckDupDoneReceiver(CheckDupDoneReceiver rcvr) {
        mDupRcvr = rcvr;
    }

    public void
    checkDupAsync(CheckDupArg arg) {
        Message msg = mBgHandler.obtainMessage(MSG_WHAT_CHECK_EXIST, arg);
        mBgHandler.sendMessage(msg);
    }

    public DBHelper() {
    }

    public void
    open() {
        HandlerThread hThread = new BGThread();
        hThread.start();
        mBgHandler = new BGHandler(hThread.getLooper(), this);
    }

    public void
    close() {
        // TODO
        // Stop running thread!
        // Need to check that below code works as expected perfectly.
        // "interrupting thread" is quite annoying and unpredictable job!
        if (null != mBgHandler) {
            mBgHandler.close();
            mBgHandler = null;
        }
    }
}
