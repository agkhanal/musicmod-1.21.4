package com.musicmod.mixin;

import com.musicmod.client.MusicScreen;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public class DisableBlurMixin {
    @Inject(method = "applyBlur", at = @At("HEAD"), cancellable = true)
    private void disableBlur(CallbackInfo ci) {
        if ((Object) this instanceof MusicScreen) {
            ci.cancel();
        }
    }
}