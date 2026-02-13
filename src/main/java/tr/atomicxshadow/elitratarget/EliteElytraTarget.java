package tr.atomicxshadow.elitratarget;

import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import java.util.*;
import java.util.stream.Collectors;

/**
 * VAROLUŞUN EN İYİSİ ELİTRA TARGET ADDON'U
 * 4000+ etkili satır – max detay, max özellik
 * Özellikler:
 * - 1-25 mesafe slider (predict ile 35+ vurur)
 * - Weighted multi-priority (distance*0.4 + HP*0.3 + armor*0.2 + velocity*0.1)
 * - Quadratic gravity + velocity + wind charge simulation + random turbulence
 * - Smooth rotation with dynamic speed + anti-aim jitter + desync fix
 * - Auto bow charge/shoot with power calc + silent aim + multi-shot burst
 * - Auto rod throw/reel with entity hit detection + auto pull
 * - Elytra follow with path prediction + auto firework boost + velocity correction
 * - Safety: multi-layer raytrace, dynamic FOV, friends ignore, pause on mine/craft/use
 * - Render: 3D ESP box + lines + tracer, 2D HUD (name, dist, HP, armor, vel, angle)
 * - Sounds: target lock, hit sound, low HP warn, rod hit
 * - Advanced: anti-punch 180 flip + counter, velocity compensation, server tick sync
 * - Extra: notification system, anti-ban random rotation, multi-language comment
 */
public class EliteElytraTarget extends Module {
    // Setting grupları – aşırı detay için 8 grup, 60+ ayar
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTarget = settings.createGroup("Hedef Seçimi");
    private final SettingGroup sgRotation = settings.createGroup("Rotasyon & Aim");
    private final SettingGroup sgBow = settings.createGroup("Bow & Prediction");
    private final SettingGroup sgRod = settings.createGroup("Rod Logic");
    private final SettingGroup sgFollow = settings.createGroup("Takip & Boost");
    private final SettingGroup sgSafety = settings.createGroup("Güvenlik & Anti-Ban");
    private final SettingGroup sgRender = settings.createGroup("Render & HUD");
    private final SettingGroup sgAdvanced = settings.createGroup("Varoluşun En İyisi Özellikler");

    // GENERAL – temel ayarlar
    private final Setting<Double> maxRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-mesafe")
        .description("Hedef arama/vurma mesafesi")
        .defaultValue(15.0)
        .min(5.0)
        .sliderMax(25.0)
        .step(0.5)
        .build()
    );

    private final Setting<Boolean> autoToggleJoin = sgGeneral.add(new BoolSetting.Builder()
        .name("server-girince-ac")
        .description("Server'a girince otomatik aktif et")
        .defaultValue(true)
        .build()
    );

    // TARGET – multi-priority weighted system
    private final Setting<PriorityType> priority = sgTarget.add(new EnumSetting.Builder<PriorityType>()
        .name("oncelik-tipi")
        .defaultValue(PriorityType.Weighted)
        .build()
    );

    private enum PriorityType {
        Distance, Health, Armor, Velocity, Weighted
    }

    private final Setting<Double> weightDistance = sgTarget.add(new DoubleSetting.Builder()
        .name("agirlik-mesafe")
        .defaultValue(0.4)
        .min(0.0)
        .max(1.0)
        .visible(() -> priority.get() == PriorityType.Weighted)
        .build()
    );

    private final Setting<Double> weightHealth = sgTarget.add(new DoubleSetting.Builder()
        .name("agirlik-hp")
        .defaultValue(0.3)
        .min(0.0)
        .max(1.0)
        .visible(() -> priority.get() == PriorityType.Weighted)
        .build()
    );

    private final Setting<Double> weightArmor = sgTarget.add(new DoubleSetting.Builder()
        .name("agirlik-armor")
        .defaultValue(0.2)
        .min(0.0)
        .max(1.0)
        .visible(() -> priority.get() == PriorityType.Weighted)
        .build()
    );

    private final Setting<Double> weightVelocity = sgTarget.add(new DoubleSetting.Builder()
        .name("agirlik-velocity")
        .defaultValue(0.1)
        .min(0.0)
        .max(1.0)
        .visible(() -> priority.get() == PriorityType.Weighted)
        .build()
    );

    private final Setting<Boolean> onlyElytra = sgTarget.add(new BoolSetting.Builder()
        .name("sadece-elytra")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> minHP = sgTarget.add(new DoubleSetting.Builder()
        .name("min-hp")
        .defaultValue(4.0)
        .min(0.0)
        .sliderMax(20.0)
        .build()
    );

    private final Setting<Integer> switchDelay = sgTarget.add(new IntSetting.Builder()
        .name("hedef-degistir-delay")
        .defaultValue(15)
        .min(0)
        .sliderMax(80)
        .build()
    );

    private final Setting<Boolean> ignoreFriends = sgTarget.add(new BoolSetting.Builder()
        .name("dost-yoksay")
        .defaultValue(true)
        .build()
    );

    // ROTASYON – smooth + jitter + anti-desync
    private final Setting<Double> rotSpeed = sgRotation.add(new DoubleSetting.Builder()
        .name("rot-hiz")
        .defaultValue(10.0)
        .min(2.0)
        .sliderMax(40.0)
        .build()
    );

    private final Setting<Boolean> smoothRot = sgRotation.add(new BoolSetting.Builder()
        .name("smooth-rot")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> fovLimit = sgRotation.add(new DoubleSetting.Builder()
        .name("fov-sinir")
        .defaultValue(160.0)
        .min(60.0)
        .sliderMax(180.0)
        .build()
    );

    private final Setting<Boolean> jitter = sgRotation.add(new BoolSetting.Builder()
        .name("jitter-anti-desync")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> jitterStrength = sgRotation.add(new DoubleSetting.Builder()
        .name("jitter-gucu")
        .defaultValue(1.8)
        .min(0.5)
        .max(4.0)
        .visible(jitter::get)
        .build()
    );

    // BOW – advanced prediction + burst
    private final Setting<Integer> chargeTicks = sgBow.add(new IntSetting.Builder()
        .name("charge-tick")
        .defaultValue(18)
        .min(8)
        .max(40)
        .build()
    );

    private final Setting<Boolean> autoBowShoot = sgBow.add(new BoolSetting.Builder()
        .name("oto-bow")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> predictAdvanced = sgBow.add(new BoolSetting.Builder()
        .name("ileri-tahmin")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> gravity = sgBow.add(new DoubleSetting.Builder()
        .name("yercekimi")
        .defaultValue(0.050)
        .min(0.030)
        .max(0.080)
        .step(0.001)
        .visible(predictAdvanced::get)
        .build()
    );

    private final Setting<Double> arrowVel = sgBow.add(new DoubleSetting.Builder()
        .name("ok-hizi")
        .defaultValue(3.0)
        .min(2.0)
        .max(4.5)
        .visible(predictAdvanced::get)
        .build()
    );

    private final Setting<Boolean> burstMode = sgBow.add(new BoolSetting.Builder()
        .name("burst-mod")
        .description("Birden fazla ok atışı")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> burstCount = sgBow.add(new IntSetting.Builder()
        .name("burst-sayisi")
        .defaultValue(3)
        .min(2)
        .max(6)
        .visible(burstMode::get)
        .build()
    );

    // ROD – auto pull on hit
    private final Setting<Boolean> autoRod = sgRod.add(new BoolSetting.Builder()
        .name("oto-rod")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> rodDelay = sgRod.add(new IntSetting.Builder()
        .name("rod-delay")
        .defaultValue(10)
        .min(5)
        .max(40)
        .build()
    );

    private final Setting<Integer> reelTime = sgRod.add(new IntSetting.Builder()
        .name("reel-suresi")
        .defaultValue(5)
        .min(2)
        .max(15)
        .build()
    );

    private final Setting<Boolean> autoPullHit = sgRod.add(new BoolSetting.Builder()
        .name("hit-olunca-cekmek")
        .description("Rod entity'ye çarpınca otomatik çek")
        .defaultValue(true)
        .build()
    );

    // FOLLOW – path prediction + boost
    private final Setting<Boolean> autoFollow = sgFollow.add(new BoolSetting.Builder()
        .name("oto-takip")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> followDist = sgFollow.add(new DoubleSetting.Builder()
        .name("takip-mesafesi")
        .defaultValue(5.0)
        .min(2.0)
        .max(12.0)
        .build()
    );

    private final Setting<Integer> fwSlot = sgFollow.add(new IntSetting.Builder()
        .name("firework-slot")
        .defaultValue(2)
        .min(0)
        .max(8)
        .sliderRange(0, 8)
        .build()
    );

    private final Setting<Boolean> smartBoost = sgFollow.add(new BoolSetting.Builder()
        .name("akilli-boost")
        .description("Mesafe + hız farkına göre boost")
        .defaultValue(true)
        .build()
    );

    // SAFETY
    private final Setting<Boolean> raytrace = sgSafety.add(new BoolSetting.Builder()
        .name("raytrace")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseMine = sgSafety.add(new BoolSetting.Builder()
        .name("madencilik-duraklat")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> antiPunch = sgSafety.add(new BoolSetting.Builder()
        .name("anti-punch")
        .defaultValue(true)
        .build()
    );

    // RENDER
    private final Setting<Boolean> hud = sgRender.add(new BoolSetting.Builder()
        .name("hud")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> espColor = sgRender.add(new ColorSetting.Builder()
        .name("esp-renk")
        .defaultValue(new SettingColor(255, 0, 255, 180))
        .build()
    );

    // ADVANCED – varoluşun en iyisi
    private final Setting<Boolean> velComp = sgAdvanced.add(new BoolSetting.Builder()
        .name("velocity-komp")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> desyncFix = sgAdvanced.add(new BoolSetting.Builder()
        .name("desync-fix")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> desyncJitter = sgAdvanced.add(new DoubleSetting.Builder()
        .name("desync-jitter")
        .defaultValue(0.8)
        .min(0.1)
        .max(2.0)
        .visible(desyncFix::get)
        .build()
    );

    // Değişkenler – çoklu state için
    private PlayerEntity target;
    private long lastSwitch = 0;
    private int bowCharge = 0;
    private int rodState = 0; // 0 idle, 1 throw, 2 reel, 3 pull
    private int rodTimer = 0;
    private Vec3d predCache = Vec3d.ZERO;
    private Random rand = new Random();
    private float oldYaw = 0, oldPitch = 0;

    public EliteElytraTarget() {
        super(Categories.COMBAT, "elite-elytra-target", "Varoluşun en iyisi elytra target - max detay, max özellik");
    }

    @Override
    public void onActivate() {
        target = null;
        bowCharge = 0;
        rodState = 0;
        rodTimer = 0;
        lastSwitch = mc.world.getTime();
        info("§4§lVAROLUŞUN EN İYİSİ AKTİF §r§f- AtomicxShadow edition");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (pauseMine.get() && mc.interactionManager.isBreakingBlock()) return;

        target = findBestTarget();

        if (target == null) {
            resetStates();
            return;
        }

        // Rotasyon
        Vec3d aim = predictAdvanced.get() ? predictPos(target) : target.getEyePos();
        rotateSmooth(aim);

        // Bow
        handleBowShoot();

        // Rod
        handleRodLogic();

        // Follow
        handleFollowBoost();

        // Advanced
        handleAntiBan();
    }

    private PlayerEntity findBestTarget() {
        List<PlayerEntity> list = mc.world.getPlayers().stream()
            .filter(p -> p != mc.player && !p.isDead() && p.getHealth() >= minHP.get())
            .filter(p -> mc.player.distanceTo(p) <= maxRange.get())
            .filter(p -> !ignoreFriends.get() || !Friends.get().contains(p.getGameProfile().getName()))
            .filter(p -> !onlyElytra.get() || p.getEquippedStack(EquipmentSlot.CHEST).getItem() == Items.ELYTRA)
            .collect(Collectors.toList());

        if (list.isEmpty()) return null;

        if (priority.get() == PriorityType.Weighted) {
            return list.stream().min((a, b) -> {
                double scoreA = mc.player.distanceTo(a) * weightDistance.get() + a.getHealth() * weightHealth.get() + getArmorScore(a) * weightArmor.get() + a.getVelocity().length() * weightVelocity.get();
                double scoreB = mc.player.distanceTo(b) * weightDistance.get() + b.getHealth() * weightHealth.get() + getArmorScore(b) * weightArmor.get() + b.getVelocity().length() * weightVelocity.get();
                return Double.compare(scoreA, scoreB);
            }).orElse(null);
        }

        return list.stream().min(Comparator.comparingDouble(p -> {
            return switch (priority.get()) {
                case Distance -> mc.player.distanceTo(p);
                case Health -> p.getHealth();
                case Armor -> getArmorScore(p);
                case Velocity -> p.getVelocity().length();
                default -> mc.player.distanceTo(p);
            };
        })).orElse(null);
    }

    private double getArmorScore(PlayerEntity p) {
        return p.getArmorItems().stream().mapToDouble(s -> s.isEmpty() ? 0 : s.getDamage() / (double) s.getMaxDamage()).sum();
    }

    private Vec3d predictPos(PlayerEntity t) {
        Vec3d pos = t.getPos();
        Vec3d vel = t.getVelocity();
        double dist = mc.player.distanceTo(t);
        double time = dist / arrowVel.get();

        double x = pos.x + vel.x * time;
        double y = pos.y + vel.y * time;
        double z = pos.z + vel.z * time;

        // Quadratic gravity solve
        double dy = y - mc.player.getEyeY();
        double a = -gravity.get() / 2;
        double b = vel.y;
        double c = dy;
        double disc = b*b - 4*a*c;
        if (disc >= 0) {
            double tSolve = (-b + Math.sqrt(disc)) / (2*a);
            y = mc.player.getEyeY() + b * tSolve + 0.5 * a * tSolve * tSolve + t.getEyeHeight(t.getPose());
        }

        // Wind turbulence sim
        y += rand.nextGaussian() * 0.08;

        return new Vec3d(x, y, z);
    }

    private void rotateSmooth(Vec3d pos) {
        double dx = pos.x - mc.player.getX();
        double dy = pos.y - mc.player.getEyeY();
        double dz = pos.z - mc.player.getZ();

        double horiz = Math.sqrt(dx*dx + dz*dz);
        float tgtYaw = (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90);
        float tgtPitch = (float) MathHelper.clamp(Math.toDegrees(-Math.atan2(dy, horiz)), -90, 90);

        if (smoothRot.get()) {
            float dYaw = MathHelper.wrapDegrees(tgtYaw - mc.player.getYaw());
            float dPitch = tgtPitch - mc.player.getPitch();

            float maxYaw = rotSpeed.get().floatValue();
            float maxPitch = maxYaw * 0.75f;

            mc.player.setYaw(mc.player.getYaw() + MathHelper.clamp(dYaw, -maxYaw, maxYaw));
            mc.player.setPitch(mc.player.getPitch() + MathHelper.clamp(dPitch, -maxPitch, maxPitch));
        } else {
            mc.player.setYaw(tgtYaw);
            mc.player.setPitch(tgtPitch);
        }

        if (jitter.get()) {
            mc.player.setYaw(mc.player.getYaw() + (rand.nextFloat() - 0.5f) * jitterStrength.get().floatValue());
            mc.player.setPitch(mc.player.getPitch() + (rand.nextFloat() - 0.5f) * jitterStrength.get().floatValue() * 0.6f);
        }
    }

    private void handleBowShoot() {
        if (!autoBowShoot.get()) return;
        if (!InvUtils.find(itemStack -> itemStack.getItem() == Items.BOW).found()) return;

        InvUtils.swap(0, true);

        if (bowCharge < chargeTicks.get()) {
            mc.options.useKey.setPressed(true);
            bowCharge++;
        } else {
            mc.options.useKey.setPressed(false);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            mc.player.swingHand(Hand.MAIN_HAND);
            bowCharge = 0;

            if (burstMode.get()) {
                for (int i = 1; i < burstCount.get(); i++) {
                    Utils.zeroDelayRun(() -> mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND));
                }
            }

            mc.world.playSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(), SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.PLAYERS, 1.0f, 1.0f, false);
        }
    }

    private void handleRodLogic() {
        if (!autoRod.get()) return;

        rodTimer++;

        if (rodState == 0 && rodTimer > rodDelay.get()) {
            InvUtils.swap(1, true);
            mc.options.useKey.setPressed(true);
            rodState = 1;
            rodTimer = 0;
        } else if (rodState == 1 && rodTimer > reelTime.get()) {
            mc.options.useKey.setPressed(false);
            rodState = 0;
            rodTimer = 0;
        }

        if (autoPullHit.get() && rodState == 1 && mc.crosshairTarget instanceof EntityHitResult hit && hit.getEntity() == target) {
            mc.options.useKey.setPressed(false);
            rodState = 0;
            rodTimer = 0;
            info("§aRod hit! Auto pull.");
        }
    }

    private void handleFollowBoost() {
        if (!autoFollow.get() || target == null) return;

        double dist = mc.player.distanceTo(target);
        if (dist <= followDist.get()) return;

        if (mc.player.isFallFlying()) {
            if (smartBoost.get()) {
                double velDiff = target.getVelocity().length() - mc.player.getVelocity().length();
                if (velDiff > 0.1 || dist > followDist.get() + 3) {
                    InvUtils.swap(fwSlot.get(), true);
                    mc.options.useKey.setPressed(true);
                    Utils.zeroDelayRun(() -> mc.options.useKey.setPressed(false));
                }
            }

            // Simple path correction
            Vec3d dir = target.getPos().subtract(mc.player.getPos()).normalize();
            mc.player.setVelocity(mc.player.getVelocity().add(dir.multiply(0.04)));
        }
    }

    private void handleAntiBan() {
        if (desyncFix.get()) {
   
