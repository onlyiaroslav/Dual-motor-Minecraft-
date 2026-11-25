package dev.starling.bridge.testmod;

import dev.starling.bridge.HeadlessModHost;
import dev.starling.bridge.NativeAction;
import dev.starling.bridge.U8Bridge;

/** Mod di esempio bridge-native: solo logica, nessun rendering. */
public final class SampleMod implements HeadlessModHost.Mod {
    @Override
    public void onEnable(U8Bridge bridge) throws Exception {
        bridge.send(NativeAction.NATIVE_HUD);
    }

    @Override
    public void onTick(U8Bridge bridge) throws Exception {
        bridge.send(NativeAction.PLAY_SOUND);
    }
}