package com.flt.carpetaddition;

import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * FLT Carpet Addition 模组入口。
 * 职责：初始化日志 + 把扩展注册给 Carpet。
 * [VERSION] Identifier.of 仅 1.19.4+；1.18.2- 用 new Identifier。
 */
public class FLTAdditionMod implements ModInitializer {
    public static final String MOD_ID = "carpet-flt-addition";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("FLT Carpet Addition initializing...");
        FLTAdditionServer.init();
    }

    /** [VERSION] Identifier.of 仅 1.19.4+；1.18.2- 用 new Identifier */
    public static Identifier id(String path) {
// IF >= fabric-1.19.4
//        return Identifier.of(MOD_ID, path);
// ELSE IF <= fabric-1.18.2
return new Identifier(MOD_ID, path);
// ELSE
//#    （理论不可达）
// END IF
    }

    /** [VERSION] 同 id() */
    public static Identifier makeId(String namespace, String path) {
// IF >= fabric-1.19.4
//        return Identifier.of(namespace, path);
// ELSE IF <= fabric-1.18.2
return new Identifier(namespace, path);
// ELSE
//#    （理论不可达）
// END IF
    }
}