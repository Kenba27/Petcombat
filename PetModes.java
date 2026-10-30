import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PetModes - che do "Canh gac" va "Hung han" cho pet cua CombatPets.
 * Plugin doc lap, khong can API cua CombatPets. Toan bo chu hien thi nam trong config.yml.
 */
public class PetModes extends JavaPlugin implements Listener {

    private enum Mode { OFF, GUARD, AGGRESSIVE }

    private static final class Prio {
        final UUID target;
        final long until;

        Prio(UUID target, long until) {
            this.target = target;
            this.until = until;
        }
    }

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final int INTERVAL = 10; // ticks giua moi lan quet

    private NamespacedKey modeKey;
    private long tick = 0;

    // pet entity -> chu (gan tay bang /petmodes bind)
    private final Map<UUID, UUID> bound = new HashMap<>();
    // pet entity -> chu (tu phat hien)
    private final Map<UUID, UUID> detected = new HashMap<>();
    // entity co dau hieu la pet cua CombatPets (du khong biet chu)
    private final Set<UUID> marked = new HashSet<>();
    // entity da kiem tra va khong phai pet, den tick nao het han cache
    private final Map<UUID, Long> notPetUntil = new HashMap<>();
    // muc tieu uu tien khi chu bi danh
    private final Map<UUID, Prio> priority = new HashMap<>();
    // tick danh gan nhat cua tung pet
    private final Map<UUID, Long> lastHit = new HashMap<>();

    private String ns;
    private double guardRadius;
    private double aggroRadius;
    private boolean includePassive;
    private int priorityTicks;
    private boolean manualDamage;
    private double fallbackDamage;
    private int attackCooldown;
    private double reach;
    private double moveSpeed;
    private final Set<EntityType> blacklist = new HashSet<>();
    private final Set<EntityType> skipManual = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        modeKey = new NamespacedKey(this, "mode");
        getServer().getPluginManager().registerEvents(this, this);
        new BukkitRunnable() {
            @Override
            public void run() {
                tick += INTERVAL;
                step();
            }
        }.runTaskTimer(this, 40L, INTERVAL);
        getLogger().info("PetModes da bat.");
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();
        ns = c.getString("pet-namespace", "combatpets").toLowerCase(Locale.ROOT);
        guardRadius = c.getDouble("guard.radius", 10.0);
        aggroRadius = c.getDouble("aggressive.radius", 50.0);
        includePassive = c.getBoolean("aggressive.include-passive", true);
        priorityTicks = c.getInt("guard.priority-seconds", 10) * 20;
        manualDamage = c.getBoolean("attack.manual-damage-for-non-monsters", true);
        fallbackDamage = c.getDouble("attack.fallback-damage", 4.0);
        attackCooldown = c.getInt("attack.cooldown-ticks", 20);
        reach = c.getDouble("attack.reach", 1.5);
        moveSpeed = c.getDouble("attack.move-speed", 1.3);
        readTypes(c.getStringList("blacklist"), blacklist, "blacklist");
        readTypes(c.getStringList("attack.skip-manual-damage"), skipManual, "attack.skip-manual-damage");
    }

    private void readTypes(List<String> names, Set<EntityType> out, String where) {
        out.clear();
        for (String s : names) {
            try {
                out.add(EntityType.valueOf(s.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                getLogger().warning(where + ": khong biet entity '" + s + "'");
            }
        }
    }

    // ------------------------------------------------------------------ tin nhan

    private void send(CommandSender to, String raw, Object... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) {
            raw = raw.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        }
        to.sendMessage(LEGACY.deserialize(raw));
    }

    private void msg(CommandSender to, String key, Object... kv) {
        send(to, getConfig().getString("messages." + key, key), kv);
    }

    // ------------------------------------------------------------------ che do

    private Mode getMode(Player p) {
        String s = p.getPersistentDataContainer().get(modeKey, PersistentDataType.STRING);
        if (s == null) return Mode.OFF;
        try {
            return Mode.valueOf(s);
        } catch (IllegalArgumentException e) {
            return Mode.OFF;
        }
    }

    private void setMode(Player p, Mode m) {
        p.getPersistentDataContainer().set(modeKey, PersistentDataType.STRING, m.name());
    }

    private String modeName(Mode m) {
        return getConfig().getString("messages.mode-" + m.name().toLowerCase(Locale.ROOT), m.name());
    }

    // ------------------------------------------------------------------ vong lap chinh

    private void step() {
        if (notPetUntil.size() > 4000) notPetUntil.values().removeIf(v -> v < tick);
        if (detected.size() > 2000) detected.clear();
        if (marked.size() > 2000) marked.clear();
        if (lastHit.size() > 1000) lastHit.clear();

        for (Player p : Bukkit.getOnlinePlayers()) {
            Mode m = getMode(p);
            if (m == Mode.OFF || p.isDead()) continue;

            List<Mob> pets = petsOf(p);
            if (pets.isEmpty()) continue;

            LivingEntity prio = currentPriority(p);
            List<LivingEntity> cands = prio != null ? List.of() : candidates(p, m);
            for (Mob pet : pets) {
                LivingEntity target = prio != null ? prio : pickTarget(pet, cands);
                if (target != null) engage(pet, target);
            }
        }
    }

    private List<Mob> petsOf(Player p) {
        List<Mob> out = new ArrayList<>();
        for (Entity e : p.getNearbyEntities(64, 64, 64)) {
            if (e instanceof Mob mob && mob.isValid() && p.getUniqueId().equals(ownerOf(mob))) {
                out.add(mob);
            }
        }
        return out;
    }

    private LivingEntity currentPriority(Player p) {
        Prio pr = priority.get(p.getUniqueId());
        if (pr == null) return null;
        Entity e = Bukkit.getEntity(pr.target);
        if (pr.until < tick || !(e instanceof LivingEntity le)
                || le.isDead() || !le.isValid() || !le.getWorld().equals(p.getWorld())) {
            priority.remove(p.getUniqueId());
            return null;
        }
        return le;
    }

    /** Quet mot lan cho ca nguoi choi, moi pet chi viec chon con gan nhat. */
    private List<LivingEntity> candidates(Player p, Mode m) {
        double r = (m == Mode.GUARD) ? guardRadius : aggroRadius;
        Location center = p.getLocation();
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : p.getWorld().getNearbyEntities(center, r, r, r)) {
            if (e instanceof LivingEntity le && isValidTarget(le, m)
                    && le.getLocation().distanceSquared(center) <= r * r) {
                out.add(le);
            }
        }
        return out;
    }

    private LivingEntity pickTarget(Mob pet, List<LivingEntity> cands) {
        if (cands.isEmpty()) return null;
        LivingEntity cur = pet.getTarget();
        if (cur != null && cands.contains(cur)) return cur; // dang danh thi cu danh tiep
        Location petLoc = pet.getLocation();
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity le : cands) {
            double d = le.getLocation().distanceSquared(petLoc);
            if (d < bestDist) {
                bestDist = d;
                best = le;
            }
        }
        return best;
    }

    private boolean isValidTarget(LivingEntity le, Mode m) {
        if (le instanceof Player || le instanceof ArmorStand) return false;
        if (le.isDead() || !le.isValid()) return false;
        if (!(le instanceof Mob mob)) return false;
        if (blacklist.contains(le.getType())) return false;
        if (le instanceof Tameable t && t.isTamed()) return false;
        if (isAnyPet(mob)) return false;
        if (le instanceof Enemy) return true; // quai gay hai: luon bi danh
        return m == Mode.AGGRESSIVE && includePassive && le.customName() == null;
    }

    private void engage(Mob pet, LivingEntity target) {
        if (pet.getTarget() != target) pet.setTarget(target);
        try {
            pet.getPathfinder().moveTo(target.getLocation(), moveSpeed);
        } catch (Throwable ignored) {
            // mot so loai mob khong ho tro pathfinder
        }
        if (!manualDamage || pet instanceof Monster || skipManual.contains(pet.getType())) return;

        long last = lastHit.getOrDefault(pet.getUniqueId(), -1000L);
        if (tick - last < attackCooldown) return;
        if (pet.getBoundingBox().expand(reach).overlaps(target.getBoundingBox())) {
            double dmg = fallbackDamage;
            AttributeInstance a = pet.getAttribute(Attribute.ATTACK_DAMAGE);
            if (a != null && a.getValue() > 0) dmg = a.getValue();
            target.damage(dmg, pet);
            lastHit.put(pet.getUniqueId(), tick);
        }
    }

    // ------------------------------------------------------------------ nhan dien pet

    private boolean isAnyPet(Mob m) {
        return ownerOf(m) != null || marked.contains(m.getUniqueId());
    }

    private UUID ownerOf(Mob e) {
        UUID id = e.getUniqueId();
        UUID o = bound.get(id);
        if (o != null) return o;
        o = detected.get(id);
        if (o != null) return o;
        Long until = notPetUntil.get(id);
        if (until != null && until > tick) return null;
        o = detect(e);
        if (o != null) {
            detected.put(id, o);
        } else {
            notPetUntil.put(id, tick + 100);
        }
        return o;
    }

    private UUID detect(Mob e) {
        if (e instanceof Tameable t) {
            UUID o = t.getOwnerUniqueId();
            if (o != null) return o;
        }
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        UUID found = null;
        for (NamespacedKey k : pdc.getKeys()) {
            if (!k.getNamespace().equalsIgnoreCase(ns)) continue;
            marked.add(e.getUniqueId());
            if (found != null) continue;
            UUID u = readUuid(pdc, k);
            if (u != null && Bukkit.getPlayer(u) != null) {
                found = u;
                continue;
            }
            String s = readString(pdc, k);
            if (s != null) {
                Player pl = Bukkit.getPlayerExact(s);
                if (pl != null) found = pl.getUniqueId();
            }
        }
        return found;
    }

    private String readString(PersistentDataContainer pdc, NamespacedKey k) {
        try {
            return pdc.get(k, PersistentDataType.STRING);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private UUID readUuid(PersistentDataContainer pdc, NamespacedKey k) {
        String s = readString(pdc, k);
        if (s != null) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
        try {
            long[] a = pdc.get(k, PersistentDataType.LONG_ARRAY);
            if (a != null && a.length == 2) return new UUID(a[0], a[1]);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            int[] a = pdc.get(k, PersistentDataType.INTEGER_ARRAY);
            if (a != null && a.length == 4) {
                long most = ((long) a[0] << 32) | (a[1] & 0xFFFFFFFFL);
                long least = ((long) a[2] << 32) | (a[3] & 0xFFFFFFFFL);
                return new UUID(most, least);
            }
        } catch (IllegalArgumentException ignored) {
        }
        try {
            byte[] b = pdc.get(k, PersistentDataType.BYTE_ARRAY);
            if (b != null && b.length == 16) {
                ByteBuffer bb = ByteBuffer.wrap(b);
                return new UUID(bb.getLong(), bb.getLong());
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ su kien

    /** Chu bi danh -> moi pet uu tien ke da danh chu (ke ca tia/mui ten thi tinh ke ban). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p) || getMode(p) == Mode.OFF) return;
        Entity d = e.getDamager();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Entity s) d = s;
        if (!(d instanceof Mob mob) || isAnyPet(mob)) return;
        priority.put(p.getUniqueId(), new Prio(mob.getUniqueId(), tick + priorityTicks));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        priority.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ lenh

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            msg(sender, "only-player");
            return true;
        }
        if (args.length == 0) {
            help(p);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "guard", "canhgac" -> {
                setMode(p, Mode.GUARD);
                msg(p, "guard-on", "radius", (int) guardRadius);
            }
            case "aggressive", "hunghan" -> {
                setMode(p, Mode.AGGRESSIVE);
                msg(p, "aggressive-on", "radius", (int) aggroRadius);
            }
            case "off", "tat" -> {
                setMode(p, Mode.OFF);
                msg(p, "off");
            }
            case "bind" -> {
                Entity t = p.getTargetEntity(10);
                if (t instanceof Mob mob) {
                    bound.put(mob.getUniqueId(), p.getUniqueId());
                    msg(p, "bind-ok", "type", mob.getType().name());
                } else {
                    msg(p, "bind-fail");
                }
            }
            case "unbind" -> {
                bound.values().removeIf(u -> u.equals(p.getUniqueId()));
                msg(p, "unbind");
            }
            case "debug" -> {
                if (!p.hasPermission("petmodes.admin")) {
                    msg(p, "no-permission");
                    return true;
                }
                debug(p);
            }
            case "reload" -> {
                if (!p.hasPermission("petmodes.admin")) {
                    msg(p, "no-permission");
                    return true;
                }
                loadSettings();
                msg(p, "reloaded");
            }
            default -> help(p);
        }
        return true;
    }

    private void help(Player p) {
        for (String line : getConfig().getStringList("messages.help")) {
            send(p, line,
                    "guard", (int) guardRadius,
                    "aggressive", (int) aggroRadius,
                    "mode", modeName(getMode(p)));
        }
    }

    private void debug(Player p) {
        Entity t = p.getTargetEntity(10);
        if (!(t instanceof Mob mob)) {
            msg(p, "debug-look");
            return;
        }
        notPetUntil.remove(mob.getUniqueId());
        UUID owner = ownerOf(mob);
        msg(p, "debug-info", "type", mob.getType().name(), "owner", owner,
                "marked", marked.contains(mob.getUniqueId()));
        int n = 0;
        PersistentDataContainer pdc = mob.getPersistentDataContainer();
        for (NamespacedKey k : pdc.getKeys()) {
            if (n++ >= 25) break;
            msg(p, "debug-key", "key", k, "value", readString(pdc, k));
        }
        if (n == 0) msg(p, "debug-none");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            List<String> o = new ArrayList<>(List.of("guard", "aggressive", "off", "bind", "unbind"));
            if (sender.hasPermission("petmodes.admin")) {
                o.add("debug");
                o.add("reload");
            }
            String pre = args[0].toLowerCase(Locale.ROOT);
            o.removeIf(x -> !x.startsWith(pre));
            return o;
        }
        return List.of();
    }
}
