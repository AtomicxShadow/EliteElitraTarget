package tr.atomicxshadow.elitratarget;

import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public class EliteElytraAddon extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger(EliteElytraAddon.class);

    @Override
    public void onInitialize() {
        LOG.info("§4§lVAROLUŞUN EN İYİSİ ELİTRA TARGET YÜKLENDİ §r§f- AtomicxShadow edition");
        LOG.info("§aMax detay, max özellik, max elite - 10000 satır sınırını zorluyoruz!");
        Modules.get().add(new EliteElytraTarget());
    }

    @Override
    public String getPackage() {
        return "tr.atomicxshadow.elitratarget";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("AtomicxShadow", "EliteElytraTarget-V2");
    }
}
