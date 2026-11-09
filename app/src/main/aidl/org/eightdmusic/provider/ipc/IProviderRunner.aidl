package org.eightdmusic.provider.ipc;

import android.os.ParcelFileDescriptor;
import org.eightdmusic.provider.ipc.INetworkBroker;

interface IProviderRunner {
    String invoke(in ParcelFileDescriptor jar, String entryClass, String requestJson, INetworkBroker broker);
}
