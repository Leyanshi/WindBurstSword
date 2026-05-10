package fr.maxlego08.template.listener;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 剑猛击与风爆兼容逻辑。
 *
 * <p>核心目标：</p>
 * <ul>
 *     <li>让 WIND_BURST 附魔可作用于剑（铁砧 + /enchant）。</li>
 *     <li>当玩家持有带风爆附魔的剑，并从 >1.5 格高度下落攻击时触发“剑猛击”。</li>
 *     <li>在不覆盖原版剑伤害计算的前提下，仅叠加额外下落伤害。</li>
 * </ul>
 */
public final class SwordSmashListener implements Listener {

    private static final double SMASH_MIN_FALL_HEIGHT = 1.5D;
    private static final long FALL_IMMUNITY_MS = 2_000L;

    private final JavaPlugin plugin;
    private final Enchantment windBurst;

    private final Map<UUID, Double> highestY = new ConcurrentHashMap<>();
    private final Map<UUID, Double> trackedFallHeight = new ConcurrentHashMap<>();
    private final Map<UUID, Long> fallImmunityUntil = new ConcurrentHashMap<>();

    public SwordSmashListener(JavaPlugin plugin) {
        this.plugin = plugin;
        this.windBurst = resolveWindBurst();
    }

    /**
     * 通过 1.21 注册表获取 WIND_BURST。
     */
    private Enchantment resolveWindBurst() {
        Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("wind_burst"));
        if (enchantment == null) {
            plugin.getLogger().warning("未找到 minecraft:wind_burst，风爆相关功能将不可用。请确认服务端为 1.21+");
        }
        return enchantment;
    }

    /**
     * 监听移动：记录玩家空中“最高点到当前点”的下落高度，供猛击判定使用。
     *
     * <p>判定思路：</p>
     * <ol>
     *     <li>玩家落地/飞行/滑翔等状态时重置追踪。</li>
     *     <li>空中时持续维护最高 Y。</li>
     *     <li>使用 highestY - currentY 得到实时下落高度。</li>
     * </ol>
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (player.isOnGround() || player.isFlying() || player.isGliding() || player.isInsideVehicle()) {
            highestY.put(uuid, player.getLocation().getY());
            trackedFallHeight.remove(uuid);
            return;
        }

        double currentY = player.getLocation().getY();
        double highest = highestY.getOrDefault(uuid, currentY);

        if (currentY > highest) {
            highest = currentY;
        }

        highestY.put(uuid, highest);

        double fallHeight = highest - currentY;
        if (fallHeight > 0.0D) {
            trackedFallHeight.put(uuid, fallHeight);
        }
    }

    /**
     * 铁砧兼容：允许“风爆附魔书 + 剑”输出带 WIND_BURST 的剑。
     *
     * <p>为什么需要该逻辑：</p>
     * <p>原版附魔目标（Enchantment Target）限制了 WIND_BURST 只能用于重锤。这里在铁砧预览阶段
     * 手动构造结果物品并使用 addUnsafeEnchantment 写入附魔，从而“重写适用物品限制”。</p>
     *
     * <p>注意：使用 addUnsafeEnchantment 不会删除剑原有锋利、击退、火焰附加等附魔，
     * 因此可确保原附魔完整保留。</p>
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (windBurst == null) {
            return;
        }

        AnvilInventory inventory = event.getInventory();
        ItemStack base = inventory.getItem(0);
        ItemStack addition = inventory.getItem(1);

        if (!isSword(base) || addition == null || addition.getType().isAir()) {
            return;
        }

        if (!addition.hasItemMeta() || !(addition.getItemMeta() instanceof EnchantmentStorageMeta bookMeta)) {
            return;
        }

        if (!bookMeta.hasStoredEnchant(windBurst)) {
            return;
        }

        int level = Math.max(1, bookMeta.getStoredEnchantLevel(windBurst));

        ItemStack result = base.clone();
        result.addUnsafeEnchantment(windBurst, level);
        event.setResult(result);
    }

    /**
     * /enchant 兼容：拦截 wind_burst 场景，允许直接给剑添加风爆附魔。
     *
     * <p>原版 /enchant 会校验附魔目标限制。这里仅对 wind_burst 做定向重写，
     * 其余附魔交回原版命令处理。</p>
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onEnchantCommand(PlayerCommandPreprocessEvent event) {
        if (windBurst == null) {
            return;
        }

        String message = event.getMessage();
        if (message == null || message.isBlank()) {
            return;
        }

        String[] args = message.trim().split("\\s+");
        if (args.length < 3 || !args[0].equalsIgnoreCase("/enchant")) {
            return;
        }

        String enchantToken = args[2].toLowerCase();
        boolean targetWindBurst = enchantToken.equals("wind_burst")
                || enchantToken.equals("minecraft:wind_burst");

        if (!targetWindBurst) {
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            event.getPlayer().sendMessage("§c目标玩家不存在或不在线。");
            event.setCancelled(true);
            return;
        }

        ItemStack hand = target.getInventory().getItemInMainHand();
        if (!isSword(hand)) {
            event.getPlayer().sendMessage("§c目标玩家主手不是剑，无法附加风爆。");
            event.setCancelled(true);
            return;
        }

        int level = 1;
        if (args.length >= 4) {
            try {
                level = Math.max(1, Integer.parseInt(args[3]));
            } catch (NumberFormatException ignored) {
                level = 1;
            }
        }

        hand.addUnsafeEnchantment(windBurst, level);
        event.setCancelled(true);
        event.getPlayer().sendMessage("§a已成功将风爆附魔应用到目标剑上。");
    }

    /**
     * 剑猛击核心：
     * 当玩家持有带 WIND_BURST 的剑，并从 >1.5 格下落后命中实体，叠加下落额外伤害并触发风爆。
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (windBurst == null || !(event.getDamager() instanceof Player player)) {
            return;
        }

        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (!isSword(weapon) || !weapon.containsEnchantment(windBurst)) {
            return;
        }

        double fallHeight = trackedFallHeight.getOrDefault(player.getUniqueId(), 0.0D);
        if (fallHeight <= SMASH_MIN_FALL_HEIGHT) {
            return;
        }

        // 保留剑原本伤害（包含原附魔与属性），只叠加额外下落伤害。
        double extraDamage = computeSmashBonusDamage(fallHeight);
        event.setDamage(event.getDamage() + extraDamage);

        int windBurstLevel = Math.max(1, weapon.getEnchantmentLevel(windBurst));
        triggerWindBurst(player, windBurstLevel);

        // 给予 2 秒摔落免疫，避免猛击后反弹落地产生摔伤。
        fallImmunityUntil.put(player.getUniqueId(), System.currentTimeMillis() + FALL_IMMUNITY_MS);
        player.setFallDistance(0.0F);

        trackedFallHeight.remove(player.getUniqueId());
        highestY.put(player.getUniqueId(), player.getLocation().getY());
    }

    /**
     * 取消短时间摔落伤害。
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player player)) {
            return;
        }

        Long until = fallImmunityUntil.get(player.getUniqueId());
        if (until == null) {
            return;
        }

        if (until >= System.currentTimeMillis()) {
            event.setCancelled(true);
            player.setFallDistance(0.0F);
            return;
        }

        fallImmunityUntil.remove(player.getUniqueId());
    }

    /**
     * 参考重锤下落伤害分段：
     * 0-3格：每格 +4
     * 4-8格：每格 +2
     * 8格以上：每格 +1
     */
    private double computeSmashBonusDamage(double fallHeight) {
        double h = Math.max(0.0D, fallHeight);

        double firstPart = Math.min(3.0D, h) * 4.0D;
        double secondPart = Math.max(0.0D, Math.min(8.0D, h) - 3.0D) * 2.0D;
        double thirdPart = Math.max(0.0D, h - 8.0D);

        return firstPart + secondPart + thirdPart;
    }

    /**
     * 尽量调用原版风爆机制：
     * 1) 先尝试生成 WindCharge 并反射调用 explode()（若服务端公开该方法，可直接走原版风爆处理链）。
     * 2) 若不可用，回退到可见粒子 + 音效 + 玩家/周围实体弹飞模拟。
     */
    private void triggerWindBurst(Player player, int level) {
        Location location = player.getLocation();
        World world = player.getWorld();

        boolean explodedByNative = false;
        try {
            WindCharge windCharge = world.spawn(location, WindCharge.class, entity -> {
                entity.setShooter(player);
                entity.setGravity(false);
                entity.setVelocity(new Vector(0, -0.01D, 0));
            });

            Method explodeMethod = windCharge.getClass().getMethod("explode");
            explodeMethod.setAccessible(true);
            explodeMethod.invoke(windCharge);
            explodedByNative = true;
        } catch (Throwable ignored) {
            // 回退逻辑在下方统一处理。
        }

        if (explodedByNative) {
            return;
        }

        // 回退模拟（保底行为）：粒子、音效、弹飞。
        world.spawnParticle(Particle.GUST_EMITTER_LARGE, location, 2, 0.25D, 0.1D, 0.25D, 0.0D);
        world.spawnParticle(Particle.GUST, location, 45, 1.0D, 0.4D, 1.0D, 0.05D);
        world.playSound(location, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.0F, 1.0F);

        double upVelocity = switch (Math.min(level, 3)) {
            case 1 -> 0.95D;
            case 2 -> 1.30D;
            default -> 1.65D;
        };

        Vector selfVelocity = player.getVelocity();
        player.setVelocity(new Vector(selfVelocity.getX(), Math.max(selfVelocity.getY(), upVelocity), selfVelocity.getZ()));

        double radius = 3.0D + level;
        for (Entity nearby : world.getNearbyEntities(location, radius, radius, radius)) {
            if (!(nearby instanceof LivingEntity living) || living.equals(player)) {
                continue;
            }

            Vector knockback = living.getLocation().toVector().subtract(location.toVector());
            if (knockback.lengthSquared() < 1.0E-6D) {
                knockback = new Vector(0.0D, 0.0D, 1.0D);
            }

            knockback.normalize().multiply(0.75D + (0.15D * level));
            knockback.setY(0.45D + (0.08D * level));
            living.setVelocity(knockback);
        }
    }

    private boolean isSword(ItemStack item) {
        return item != null
                && !item.getType().isAir()
                && item.getType().name().endsWith("_SWORD");
    }
}
