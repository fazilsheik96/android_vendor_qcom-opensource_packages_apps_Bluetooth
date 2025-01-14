/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Changes from Qualcomm Innovation Center are provided under the following license:
 * Copyright (c) 2022 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 *
*/

package com.android.bluetooth.mapclient;

import com.android.vcard.VCardEntry;
import com.android.vcard.VCardEntry.EmailData;
import com.android.vcard.VCardEntry.NameData;
import com.android.vcard.VCardEntry.PhoneData;
import java.util.UUID;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.List;
import android.util.Log;

/* BMessage as defined by MAP_SPEC_V101 Section 3.1.3 Message format (x-bt/message) */
class BmessageBuilder {
    private static final String CRLF = "\r\n";
    private static final String BMSG_BEGIN = "BEGIN:BMSG";
    private static final String BMSG_VERSION = "VERSION:1.0";
    private static final String BMSG_STATUS = "STATUS:";
    private static final String BMSG_TYPE = "TYPE:";
    private static final String BMSG_FOLDER = "FOLDER:";
    private static final String BMSG_END = "END:BMSG";

    private static final String BENV_BEGIN = "BEGIN:BENV";
    private static final String BENV_END = "END:BENV";

    private static final String BBODY_BEGIN = "BEGIN:BBODY";
    private static final String BBODY_ENCODING = "ENCODING:";
    private static final String BBODY_CHARSET = "CHARSET:";
    private static final String BBODY_LANGUAGE = "LANGUAGE:";
    private static final String BBODY_LENGTH = "LENGTH:";
    private static final String BBODY_END = "END:BBODY";

    private static final String MSG_BEGIN = "BEGIN:MSG";
    private static final String MSG_END = "END:MSG";

    private static final String VCARD_BEGIN = "BEGIN:VCARD";
    private static final String VCARD_VERSION = "VERSION:2.1";
    private static final String VCARD_N = "N:";
    private static final String VCARD_EMAIL = "EMAIL:";
    private static final String VCARD_TEL = "TEL:";
    private static final String VCARD_END = "END:VCARD";

    private final StringBuilder mBmsg;

    private BmessageBuilder() {
        mBmsg = new StringBuilder();
    }

    public static String createBmessage(Bmessage bmsg) {
        BmessageBuilder b = new BmessageBuilder();
        if(bmsg.IsImageMMS()) {
            b.buildMMS(bmsg);
        } else {
            b.build(bmsg);
        }
        return b.mBmsg.toString();
    }

    private void build(Bmessage bmsg) {
        int bodyLen = MSG_BEGIN.length() + MSG_END.length() + 3 * CRLF.length()
                + bmsg.mMessage.getBytes().length;

        mBmsg.append(BMSG_BEGIN).append(CRLF);

        mBmsg.append(BMSG_VERSION).append(CRLF);
        mBmsg.append(BMSG_STATUS).append(bmsg.mBmsgStatus).append(CRLF);
        mBmsg.append(BMSG_TYPE).append(bmsg.mBmsgType).append(CRLF);
        mBmsg.append(BMSG_FOLDER).append(bmsg.mBmsgFolder).append(CRLF);

        for (VCardEntry vcard : bmsg.mOriginators) {
            buildVcard(vcard);
        }

        {
            mBmsg.append(BENV_BEGIN).append(CRLF);

            for (VCardEntry vcard : bmsg.mRecipients) {
                buildVcard(vcard);
            }

            {
                mBmsg.append(BBODY_BEGIN).append(CRLF);

                if (bmsg.mBbodyEncoding != null) {
                    mBmsg.append(BBODY_ENCODING).append(bmsg.mBbodyEncoding).append(CRLF);
                }

                if (bmsg.mBbodyCharset != null) {
                    mBmsg.append(BBODY_CHARSET).append(bmsg.mBbodyCharset).append(CRLF);
                }

                if (bmsg.mBbodyLanguage != null) {
                    mBmsg.append(BBODY_LANGUAGE).append(bmsg.mBbodyLanguage).append(CRLF);
                }

                mBmsg.append(BBODY_LENGTH).append(bodyLen).append(CRLF);

                {
                    mBmsg.append(MSG_BEGIN).append(CRLF);

                    mBmsg.append(bmsg.mMessage).append(CRLF);

                    mBmsg.append(MSG_END).append(CRLF);
                }

                mBmsg.append(BBODY_END).append(CRLF);
            }

            mBmsg.append(BENV_END).append(CRLF);
        }

        mBmsg.append(BMSG_END).append(CRLF);
    }

    private void buildVcard(VCardEntry vcard) {
        String n = buildVcardN(vcard);
        List<PhoneData> tel = vcard.getPhoneList();
        List<EmailData> email = vcard.getEmailList();

        mBmsg.append(VCARD_BEGIN).append(CRLF);

        mBmsg.append(VCARD_VERSION).append(CRLF);

        mBmsg.append(VCARD_N).append(n).append(CRLF);

        if (tel != null && tel.size() > 0) {
            mBmsg.append(VCARD_TEL).append(tel.get(0).getNumber()).append(CRLF);
        }

        if (email != null && email.size() > 0) {
            mBmsg.append(VCARD_EMAIL).append(email.get(0).getAddress()).append(CRLF);
        }

        mBmsg.append(VCARD_END).append(CRLF);
    }

    private String buildVcardN(VCardEntry vcard) {
        NameData nd = vcard.getNameData();
        StringBuilder sb = new StringBuilder();

        sb.append(nd.getFamily()).append(";");
        sb.append(nd.getGiven() == null ? "" : nd.getGiven()).append(";");
        sb.append(nd.getMiddle() == null ? "" : nd.getMiddle()).append(";");
        sb.append(nd.getPrefix() == null ? "" : nd.getPrefix()).append(";");
        sb.append(nd.getSuffix() == null ? "" : nd.getSuffix());

        return sb.toString();
    }

     private void buildMMS(Bmessage bmsg) {
        String boundary = getBoundary();
        String ToField ="";
        int defaultBodyLen = 9999;
        //Beginning of BMSG
        mBmsg.append(BMSG_BEGIN).append(CRLF);
        mBmsg.append(BMSG_VERSION).append(CRLF);
        mBmsg.append(BMSG_STATUS).append(bmsg.mBmsgStatus).append(CRLF);
        mBmsg.append(BMSG_TYPE).append(bmsg.mBmsgType).append(CRLF);
        mBmsg.append(BMSG_FOLDER).append(bmsg.mBmsgFolder).append(CRLF);

        for (VCardEntry vcard : bmsg.mOriginators) {
            buildVcard(vcard);
        }
        //Beginning of BENV
        mBmsg.append(BENV_BEGIN).append(CRLF);
        for (VCardEntry vcard : bmsg.mRecipients) {
            buildVcard(vcard);
            ToField = getTo(vcard);
        }
        //Beginning of Body
        mBmsg.append(BBODY_BEGIN).append(CRLF);

        if (bmsg.mBbodyEncoding != null) {
            mBmsg.append(BBODY_ENCODING).append("8BIT").append(CRLF);
        }
        mBmsg.append(BBODY_LENGTH).append(defaultBodyLen).append(CRLF);

        int length1 = mBmsg.length();
        //Beginning of MSG
        mBmsg.append(MSG_BEGIN).append(CRLF);
        mBmsg.append("Date: "+getTime()).append(CRLF);
        mBmsg.append("From: <0000000000>;").append(CRLF);
        mBmsg.append("To: <"+ToField+">;").append(CRLF);
        mBmsg.append("Content-Type: application/vnd.wap.multipart.related; boundary=--=_"+boundary).append(CRLF);
        mBmsg.append(CRLF);
        mBmsg.append("----=_"+boundary).append(CRLF);
        mBmsg.append("Content-Type: application/smil; charset=\"utf-8\"").append(CRLF);
        mBmsg.append("Content-Location: smil.xml").append(CRLF);
        mBmsg.append("Content-ID: <smil>").append(CRLF);
        mBmsg.append("Content-Transfer-Encoding: 8BIT").append(CRLF);
        mBmsg.append(CRLF);
        mBmsg.append("<smil xmlns=\"http://www.w3.org/2001/SMIL20/Language\"><head><layout/></head><body><par dur=\"8000ms\"><img src=\"image\"/></par></body></smil>").append(CRLF);
        mBmsg.append("----=_"+boundary).append(CRLF);
        mBmsg.append("Content-Type: image/"+bmsg.getFileType()).append(CRLF);
        mBmsg.append("Content-Location: image").append(CRLF);
        mBmsg.append("Content-ID: <image>").append(CRLF);
        mBmsg.append("Content-Transfer-Encoding: Base64").append(CRLF);
        mBmsg.append(CRLF);
        mBmsg.append(bmsg.mMessage).append(CRLF);
        mBmsg.append("----=_"+boundary+"--").append(CRLF);
        mBmsg.append(CRLF);
        //End of MSG
        mBmsg.append(MSG_END).append(CRLF);
        int length2 = mBmsg.length();
        int finalLen = length2 -length1;
        int index = mBmsg.indexOf("9999");
        mBmsg.replace(index,index+4,""+finalLen);
        //End of Body
        mBmsg.append(BBODY_END).append(CRLF);
        //End of BENV
        mBmsg.append(BENV_END).append(CRLF);
        //End of BMSG
        mBmsg.append(BMSG_END).append(CRLF);
    }

    private String getTime() {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
        Date date = new Date();
        return format.format(date).toString();
    }

    private String getBoundary() {
        return UUID.randomUUID().toString();
    }

    private String getTo(VCardEntry vcard) {
        List<PhoneData> tel = vcard.getPhoneList();
        if (tel != null && tel.size() > 0) {
            String Number= tel.get(0).getNumber();
            String ReplacedNumber= Number.replace("-","");
            return ReplacedNumber;
        }
        else {
            return null;
        }
    }
}