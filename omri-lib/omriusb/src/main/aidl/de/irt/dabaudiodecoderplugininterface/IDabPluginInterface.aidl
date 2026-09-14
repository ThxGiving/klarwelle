package de.irt.dabaudiodecoderplugininterface;

import de.irt.dabaudiodecoderplugininterface.IDabPluginCallback;

interface IDabPluginInterface {
    void setCallback(IDabPluginCallback callback);
    void enqueueEncodedData(in byte[] encodedData);
}
