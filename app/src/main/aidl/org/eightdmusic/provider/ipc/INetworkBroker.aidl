package org.eightdmusic.provider.ipc;

import android.os.ParcelFileDescriptor;

interface INetworkBroker {
    String request(String requestJson);
    ParcelFileDescriptor requestFile(String requestJson);
}
