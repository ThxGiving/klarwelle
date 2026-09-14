package de.irt.dabaudiodecoderplugininterface;

interface IDabPluginCallback {
    void decodedPcmData(in byte[] pcmData);
}
