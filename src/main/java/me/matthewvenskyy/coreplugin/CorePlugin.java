package me.matthewvenskyy.coreplugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

public class CorePlugin extends JavaPlugin implements Listener, TabExecutor {

    private NamespacedKey coreItemKey;
    private NamespacedKey corebreakerKey;
    private NamespacedKey coreOwnerKey;
    private NamespacedKey coreOwnerNameKey;

    private File dataFile;
    private FileConfiguration data;

    private Material coreMaterial;
    private Material corebreakerMaterial;
    private boolean requireOnlineOwnerToBreak;
    private boolean giveStartingCorebreaker;
    private boolean giveStartingCore;
    private int selfdestructCooldownSeconds;
    private int respawnSearchRadius;
    private int offlineProtectionGraceSeconds;
    private int offlineCoreExpireDays;
    private int coreTeleportDelaySeconds;
    private int coreBreakMiningFatigueSeconds;
    private int coreHoldMaxSeconds;

    private final Map<UUID, Location> pendingCoreDeaths = new HashMap<>();
    private final Map<UUID, Long> selfdestructCooldowns = new HashMap<>();
    private final Map<UUID, Boolean> pendingCoreHoldDeaths = new HashMap<>();
    private final Map<UUID, Boolean> pendingSelfdestructDeaths = new HashMap<>();
    private final Map<UUID, BlockDisplay> coreGlowDisplays = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        loadData();

        coreItemKey = new NamespacedKey(this, "core_item");
        corebreakerKey = new NamespacedKey(this, "corebreaker");
        coreOwnerKey = new NamespacedKey(this, "core_owner");
        coreOwnerNameKey = new NamespacedKey(this, "core_owner_name");

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("kills")).setExecutor(this);
        Objects.requireNonNull(getCommand("core")).setExecutor(this);
        Objects.requireNonNull(getCommand("selfdestruct")).setExecutor(this);
        startCorebreakerDisplayTask();
        startCoreHoldTimerTask();
        startCoreGlowTask();
        startExpiredCoreCleanupTask();

        getLogger().info("CorePlugin enabled.");
    }

    @Override
    public void onDisable() {
        clearCoreGlowDisplays();
        saveData();
        getLogger().info("CorePlugin disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("kills")) {
            showKills(player);
            return true;
        }

        if (command.getName().equalsIgnoreCase("core")) {
            teleportToCore(player);
            return true;
        }

        if (command.getName().equalsIgnoreCase("selfdestruct")) {
            selfdestruct(player);
            return true;
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String base = playerPath(player.getUniqueId());
        data.set(base + ".last-seen", System.currentTimeMillis());
        if (!data.contains(base + ".core-hold-seconds")) {
            data.set(base + ".core-hold-seconds", coreHoldMaxSeconds);
        }
        if (!data.getBoolean(base + ".default-charge-granted", false)) {
            addDefaultCharge(player.getUniqueId());
            data.set(base + ".default-charge-granted", true);
        }
        if (!data.getBoolean(base + ".initialized", false)) {
            if (giveStartingCore) {
                giveOrDrop(player, createCoreItem(player));
            }
            data.set(base + ".initialized", true);
        }

        if (giveStartingCorebreaker) {
            ensureCorebreaker(player);
        }
        updateCorebreakers(player);
        saveData();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removeCoreGlow(event.getPlayer().getUniqueId());
        data.set(playerPath(event.getPlayer().getUniqueId()) + ".last-seen", System.currentTimeMillis());
        saveData();
    }

    @EventHandler(ignoreCancelled = true)
    public void onCorePlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        Player player = event.getPlayer();
        if (!isCoreItem(item) && wouldActivateCoreBeacon(event.getBlockPlaced())) {
            event.setCancelled(true);
            send(player, "&cBeacon base blocks cannot be placed under a core.");
            return;
        }

        if (!isCoreItem(item)) {
            return;
        }

        Block block = event.getBlockPlaced();
        if (!tryRegisterPlacedCore(player, item, block)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onCoreBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        CoreBlock core = getCoreBlock(block);
        if (core == null) {
            if (isCorebreaker(event.getPlayer().getInventory().getItemInMainHand())) {
                event.setCancelled(true);
                send(event.getPlayer(), "&cCorebreakers can only break player cores.");
                return;
            }

            CoreBlock nearbyCore = getNearbyCore(block, 1);
            if (nearbyCore != null) {
                alertCoreOwner(nearbyCore, event.getPlayer(), block);
            }
            return;
        }

        Player breaker = event.getPlayer();
        if (breaker.getUniqueId().equals(core.ownerId())) {
            event.setCancelled(true);
            send(breaker, "&cUse /selfdestruct to remove your own core.");
            return;
        }

        if (!isCorebreaker(breaker.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            send(breaker, "&cOnly a Corebreaker can destroy a player core.");
            return;
        }

        if (getCharges(breaker) < 1) {
            event.setCancelled(true);
            send(breaker, "&cYour Corebreaker has no charges. Get a unique player kill first.");
            updateCorebreakers(breaker);
            return;
        }

        Player owner = Bukkit.getPlayer(core.ownerId());
        if (owner == null && isOfflineProtected(core.ownerId())) {
            event.setCancelled(true);
            send(breaker, "&cThat player's core is protected while they are offline.");
            return;
        }

        event.setCancelled(true);
        consumeOldestKill(breaker);
        updateCorebreakers(breaker);
        removeCore(core.ownerId());
        removeCoreGlow(core.ownerId());
        block.setType(Material.AIR);

        if (owner != null) {
            pendingCoreDeaths.put(owner.getUniqueId(), block.getLocation().add(0.5, 0.5, 0.5));
            owner.setHealth(0.0);
            send(owner, "&cYour core was destroyed by " + breaker.getName() + ".");
        }

        send(breaker, "&aYou destroyed " + core.ownerName() + "'s core.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        CoreBlock nearbyCore = getNearbyCore(event.getBlock(), 2);
        if (nearbyCore != null) {
            int durationSeconds = Math.min(coreBreakMiningFatigueSeconds, 3);
            event.getPlayer().addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, durationSeconds * 20, 0, true, true, true));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Player killer = player.getKiller();
        Location coreDeathLocation = pendingCoreDeaths.remove(player.getUniqueId());
        boolean coreHoldDeath = pendingCoreHoldDeaths.remove(player.getUniqueId()) != null;
        boolean selfdestructDeath = pendingSelfdestructDeaths.remove(player.getUniqueId()) != null;
        boolean hadCoreItem = removeBoundItemsFromDrops(event.getDrops());

        if (coreDeathLocation != null) {
            List<ItemStack> drops = new ArrayList<>(event.getDrops());
            event.getDrops().clear();
            for (ItemStack drop : drops) {
                if (drop != null && !drop.getType().isAir()) {
                    coreDeathLocation.getWorld().dropItemNaturally(coreDeathLocation, drop);
                }
            }
            data.set(playerPath(player.getUniqueId()) + ".needs-core-on-respawn", true);
            saveData();
            return;
        }

        if (coreHoldDeath || selfdestructDeath) {
            data.set(playerPath(player.getUniqueId()) + ".needs-core-item-on-respawn", true);
            setCoreHoldSeconds(player.getUniqueId(), coreHoldMaxSeconds);
            saveData();
        } else if (hadCoreItem && getCoreLocation(player.getUniqueId()) == null) {
            data.set(playerPath(player.getUniqueId()) + ".needs-core-item-on-respawn", true);
            saveData();
        }

        if (killer != null && !killer.getUniqueId().equals(player.getUniqueId())) {
            addUniqueKill(killer, player);
            if (!hasCorebreaker(killer)) {
                forceGiveCorebreaker(killer);
            }
            updateCorebreakers(killer);
            send(killer, "&aUnique kill recorded. Corebreaker charges: &e" + getCharges(killer));
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        String base = playerPath(player.getUniqueId());
        if (data.getBoolean(base + ".needs-core-on-respawn", false)) {
            Location bed = player.getRespawnLocation();
            if (bed != null) {
                event.setRespawnLocation(bed);
            }

            data.set(base + ".needs-core-on-respawn", false);
            Bukkit.getScheduler().runTaskLater(this, () -> giveCoreItemIfMissing(player), 1L);
            saveData();
            return;
        }

        if (data.getBoolean(base + ".needs-core-item-on-respawn", false)) {
            data.set(base + ".needs-core-item-on-respawn", false);
            Bukkit.getScheduler().runTaskLater(this, () -> giveCoreItemIfMissing(player), 1L);
            saveData();
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            ensureCorebreaker(player);
            updateCorebreakers(player);
        }, 1L);

        Location coreLocation = getCoreLocation(player.getUniqueId());
        Location respawnLocation = findSafeRespawn(coreLocation);
        if (respawnLocation != null) {
            event.setRespawnLocation(respawnLocation);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (shouldCancelBoundInventoryClick(event)) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                send(player, "&cCore items cannot be dropped, traded, or stored.");
            }
            return;
        }

        if (event.getWhoClicked() instanceof Player player) {
            Bukkit.getScheduler().runTaskLater(this, () -> updateCorebreakers(player), 1L);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!isBoundItem(event.getOldCursor())) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < topSize) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    send(player, "&cCore items cannot be stored in containers.");
                }
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (isBoundItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            send(event.getPlayer(), "&cCore items are bound to your inventory.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        ItemStack item = event.getItem().getItemStack();
        String coreOwnerId = getCoreItemOwnerId(item);
        if (coreOwnerId != null && !coreOwnerId.equals(player.getUniqueId().toString())) {
            event.setCancelled(true);
            return;
        }

        if (isCorebreaker(item)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }

        if (getCoreBlock(event.getClickedBlock()) != null) {
            event.setCancelled(true);
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (!isCoreItem(item) || event.getBlockFace() == BlockFace.SELF) {
            return;
        }

        Block target = event.getClickedBlock().getRelative(event.getBlockFace());
        if (!canManuallyPlaceCoreAt(target)) {
            return;
        }

        event.setCancelled(true);
        target.setType(coreMaterial);
        if (!tryRegisterPlacedCore(player, item, target)) {
            target.setType(Material.AIR);
            return;
        }

        item.setAmount(item.getAmount() - 1);
        player.swingMainHand();
    }

    private boolean tryRegisterPlacedCore(Player player, ItemStack item, Block block) {
        String itemOwnerId = getCoreItemOwnerId(item);
        if (itemOwnerId == null || !itemOwnerId.equals(player.getUniqueId().toString())) {
            send(player, "&cYou cannot place another player's core.");
            return false;
        }

        if (getCoreLocation(player.getUniqueId()) != null) {
            send(player, "&cYou already have a placed core. Use /selfdestruct to move it.");
            return false;
        }

        if (block.getType() != coreMaterial || !(block.getState() instanceof TileState tileState)) {
            send(player, "&cThis core material cannot store ownership data.");
            return false;
        }

        if (hasBeaconBaseBelow(block.getLocation())) {
            send(player, "&cCores cannot be placed on beacon bases.");
            return false;
        }

        PersistentDataContainer container = tileState.getPersistentDataContainer();
        container.set(coreOwnerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        container.set(coreOwnerNameKey, PersistentDataType.STRING, player.getName());
        tileState.update(true);
        setCoreLocation(player.getUniqueId(), block.getLocation());
        send(player, "&aCore placed. Normal deaths now respawn you near it.");
        return true;
    }

    private boolean canManuallyPlaceCoreAt(Block block) {
        Material type = block.getType();
        return type == Material.AIR
                || type == Material.CAVE_AIR
                || type == Material.VOID_AIR
                || type == Material.WATER
                || type == Material.LAVA;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerItemDamage(PlayerItemDamageEvent event) {
        if (isCorebreaker(event.getItem())) {
            event.setCancelled(true);
        }
    }

    private void showKills(Player player) {
        List<String> kills = getKillQueue(player.getUniqueId());
        if (kills.isEmpty()) {
            send(player, "&7Your kill queue is empty. Corebreaker charges: &e0");
            return;
        }

        send(player, "&7Corebreaker charges: &e" + kills.size());
        for (int i = 0; i < kills.size(); i++) {
            String killedId = kills.get(i);
            String name = data.getString(playerPath(player.getUniqueId()) + ".kill-names." + killedId, killedId);
            player.sendMessage(color("&8" + (i + 1) + ". &f" + name));
        }
    }

    private void selfdestruct(Player player) {
        if (!player.hasPermission("coreplugin.selfdestruct")) {
            send(player, "&cYou do not have permission to use this command.");
            return;
        }

        long now = System.currentTimeMillis();
        long availableAt = selfdestructCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (availableAt > now) {
            long seconds = Math.max(1L, (availableAt - now) / 1000L);
            send(player, "&cYou can selfdestruct again in " + seconds + " seconds.");
            return;
        }

        Location coreLocation = getCoreLocation(player.getUniqueId());
        if (coreLocation == null) {
            send(player, "&cYou do not have a placed core.");
            return;
        }

        Block block = coreLocation.getBlock();
        CoreBlock core = getCoreBlock(block);
        if (core != null && core.ownerId().equals(player.getUniqueId())) {
            block.setType(Material.AIR);
        }

        removeCore(player.getUniqueId());
        removeCoreGlow(player.getUniqueId());
        dropInventoryForSelfdestruct(player);
        pendingSelfdestructDeaths.put(player.getUniqueId(), true);
        selfdestructCooldowns.put(player.getUniqueId(), now + selfdestructCooldownSeconds * 1000L);
        send(player, "&cYour core selfdestructed.");
        player.setHealth(0.0);
    }

    private void teleportToCore(Player player) {
        Location coreLocation = getCoreLocation(player.getUniqueId());
        Location destination = findSafeRespawn(coreLocation);
        if (destination == null) {
            send(player, "&cYou do not have a safe core teleport location.");
            return;
        }

        send(player, "&aTeleporting to your core in " + coreTeleportDelaySeconds + " seconds.");
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) {
                return;
            }

            Location latestCoreLocation = getCoreLocation(player.getUniqueId());
            Location latestDestination = findSafeRespawn(latestCoreLocation);
            if (latestDestination == null) {
                send(player, "&cYour core teleport failed because the destination is no longer safe.");
                return;
            }

            player.teleport(latestDestination);
            send(player, "&aTeleported to your core.");
        }, coreTeleportDelaySeconds * 20L);
    }

    private void addUniqueKill(Player killer, Player victim) {
        UUID killerId = killer.getUniqueId();
        String victimId = victim.getUniqueId().toString();
        List<String> kills = getKillQueue(killerId);
        if (kills.contains(victimId)) {
            return;
        }

        kills.add(victimId);
        String base = playerPath(killerId);
        data.set(base + ".kills", kills);
        data.set(base + ".kill-names." + victimId, victim.getName());
        saveData();
    }

    private void addDefaultCharge(UUID playerId) {
        List<String> kills = getKillQueue(playerId);
        if (kills.contains("default")) {
            return;
        }

        kills.add("default");
        String base = playerPath(playerId);
        data.set(base + ".kills", kills);
        data.set(base + ".kill-names.default", "default");
    }

    private void consumeOldestKill(Player player) {
        List<String> kills = getKillQueue(player.getUniqueId());
        if (kills.isEmpty()) {
            return;
        }

        String removed = kills.remove(0);
        String base = playerPath(player.getUniqueId());
        data.set(base + ".kills", kills);
        data.set(base + ".kill-names." + removed, null);
        saveData();
    }

    private List<String> getKillQueue(UUID playerId) {
        return new ArrayList<>(data.getStringList(playerPath(playerId) + ".kills"));
    }

    private int getCharges(Player player) {
        return getKillQueue(player.getUniqueId()).size();
    }

    private ItemStack createCoreItem(Player owner) {
        return createCoreItem(owner.getUniqueId(), owner.getName());
    }

    private ItemStack createCoreItem(UUID ownerId, String ownerName) {
        ItemStack item = new ItemStack(coreMaterial);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color("&b" + ownerName + "'s Core"));
        meta.setLore(List.of(
                color("&7Place this to anchor your respawn."),
                color("&7If another player breaks it, you die."),
                color("&8Bound to " + ownerName + "."),
                color("&8Only Corebreakers can destroy cores.")));
        meta.getPersistentDataContainer().set(coreItemKey, PersistentDataType.STRING, ownerId.toString());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createCorebreaker(int charges) {
        ItemStack item = new ItemStack(corebreakerMaterial);
        updateCorebreakerMeta(item, charges);
        return item;
    }

    private ItemStack withCorebreakerCharges(ItemStack source, int charges) {
        ItemStack item = source.clone();
        updateCorebreakerMeta(item, charges);
        return item;
    }

    private void updateCorebreakerMeta(ItemStack item, int charges) {
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(getConfig().getString("corebreaker.name", "&cCorebreaker")));
        meta.setLore(List.of(color("&7Charges: &e" + charges)));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(corebreakerKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
    }

    private boolean isCoreItem(ItemStack item) {
        if (item == null || item.getType() != coreMaterial || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(coreItemKey, PersistentDataType.STRING);
    }

    private String getCoreItemOwnerId(ItemStack item) {
        if (item == null || item.getType() != coreMaterial || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(coreItemKey, PersistentDataType.STRING);
    }

    private boolean isCorebreaker(ItemStack item) {
        if (item == null || item.getType() != corebreakerMaterial || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(corebreakerKey, PersistentDataType.BYTE);
    }

    private boolean hasCorebreaker(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isCorebreaker(item)) {
                return true;
            }
        }
        return false;
    }

    private void ensureCorebreaker(Player player) {
        if (!hasCorebreaker(player)) {
            forceGiveCorebreaker(player);
        }
    }

    private void forceGiveCorebreaker(Player player) {
        ItemStack corebreaker = createCorebreaker(getCharges(player));
        PlayerInventory inventory = player.getInventory();
        int emptySlot = inventory.firstEmpty();
        if (emptySlot >= 0) {
            inventory.setItem(emptySlot, corebreaker);
            return;
        }

        int weldedSlot = 8;
        ItemStack displaced = inventory.getItem(weldedSlot);
        inventory.setItem(weldedSlot, corebreaker);
        if (displaced != null && !displaced.getType().isAir()) {
            player.getWorld().dropItemNaturally(player.getLocation(), displaced);
        }
    }

    private void updateCorebreakers(Player player) {
        int charges = getCharges(player);
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack item = inventory.getItem(i);
            if (isCorebreaker(item)) {
                inventory.setItem(i, withCorebreakerCharges(item, charges));
            }
        }

        ItemStack offhand = inventory.getItemInOffHand();
        if (isCorebreaker(offhand)) {
            inventory.setItemInOffHand(withCorebreakerCharges(offhand, charges));
        }
    }

    private void startCorebreakerDisplayTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    updateCorebreakers(player);
                }
            }
        }.runTaskTimer(this, 40L, 40L);
    }

    private void startCoreHoldTimerTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                tickCoreHoldTimers();
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    private void startCoreGlowTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                updateCoreGlows();
            }
        }.runTaskTimer(this, 10L, 10L);
    }

    private void updateCoreGlows() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location coreLocation = getCoreLocation(player.getUniqueId());
            if (coreLocation == null || coreLocation.getWorld() == null || !player.getWorld().equals(coreLocation.getWorld())) {
                removeCoreGlow(player.getUniqueId());
                continue;
            }

            if (player.getLocation().distanceSquared(coreLocation.clone().add(0.5, 0.5, 0.5)) > 25.0) {
                removeCoreGlow(player.getUniqueId());
                continue;
            }

            showCoreGlow(player, coreLocation);
        }
    }

    private void showCoreGlow(Player player, Location coreLocation) {
        UUID playerId = player.getUniqueId();
        BlockDisplay display = coreGlowDisplays.get(playerId);
        Location displayLocation = coreLocation.clone().add(0.0, 0.0, 0.0);
        if (display == null || display.isDead() || !display.getWorld().equals(coreLocation.getWorld())) {
            display = coreLocation.getWorld().spawn(displayLocation, BlockDisplay.class, spawned -> {
                spawned.setBlock(coreMaterial.createBlockData());
                spawned.setGlowing(true);
                spawned.setVisibleByDefault(false);
                spawned.setPersistent(false);
                spawned.setInvulnerable(true);
            });
            coreGlowDisplays.put(playerId, display);
            player.showEntity(this, display);
            return;
        }

        if (display.getLocation().distanceSquared(displayLocation) > 0.01) {
            display.teleport(displayLocation);
        }
        player.showEntity(this, display);
    }

    private void removeCoreGlow(UUID playerId) {
        BlockDisplay display = coreGlowDisplays.remove(playerId);
        if (display != null && !display.isDead()) {
            display.remove();
        }
    }

    private void clearCoreGlowDisplays() {
        for (BlockDisplay display : coreGlowDisplays.values()) {
            if (display != null && !display.isDead()) {
                display.remove();
            }
        }
        coreGlowDisplays.clear();
    }

    private void tickCoreHoldTimers() {
        boolean changed = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            Location coreLocation = getCoreLocation(playerId);
            if (coreLocation != null) {
                int seconds = getCoreHoldSeconds(playerId);
                if (seconds < coreHoldMaxSeconds) {
                    setCoreHoldSeconds(playerId, Math.min(coreHoldMaxSeconds, seconds + 1));
                    changed = true;
                }
                showCoreHoldTimer(player, getCoreHoldSeconds(playerId), true);
                continue;
            }

            if (!hasOwnCoreItem(player)) {
                continue;
            }

            int seconds = Math.max(0, getCoreHoldSeconds(playerId) - 1);
            setCoreHoldSeconds(playerId, seconds);
            changed = true;
            showCoreHoldTimer(player, seconds, false);

            if (seconds == 60 || seconds == 30 || seconds == 10 || seconds <= 5 && seconds > 0) {
                send(player, "&cPlace your core within " + seconds + " seconds or you will die.");
            }

            if (seconds <= 0) {
                pendingCoreHoldDeaths.put(playerId, true);
                send(player, "&cYou held your core for too long.");
                player.setHealth(0.0);
            }
        }

        if (changed) {
            saveData();
        }
    }

    private void startExpiredCoreCleanupTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                destroyExpiredOfflineCores();
            }
        }.runTaskTimer(this, 20L, 20L * 60L * 10L);
    }

    private void destroyExpiredOfflineCores() {
        long now = System.currentTimeMillis();
        long expireMillis = offlineCoreExpireDays * 24L * 60L * 60L * 1000L;
        if (expireMillis <= 0L) {
            return;
        }

        for (String playerIdText : data.getConfigurationSection("players") == null
                ? List.<String>of()
                : data.getConfigurationSection("players").getKeys(false)) {
            UUID playerId;
            try {
                playerId = UUID.fromString(playerIdText);
            } catch (IllegalArgumentException exception) {
                continue;
            }

            if (Bukkit.getPlayer(playerId) != null) {
                continue;
            }

            long lastSeen = data.getLong(playerPath(playerId) + ".last-seen", now);
            if (now - lastSeen < expireMillis) {
                continue;
            }

            Location coreLocation = getCoreLocation(playerId);
            if (coreLocation == null) {
                continue;
            }

            Block block = coreLocation.getBlock();
            CoreBlock core = getCoreBlock(block);
            if (core != null && core.ownerId().equals(playerId)) {
                block.setType(Material.AIR);
                coreLocation.getWorld().dropItemNaturally(coreLocation.add(0.5, 0.5, 0.5), createCoreItem(core.ownerId(), core.ownerName()));
                getLogger().info("Destroyed expired offline core for " + core.ownerName() + ".");
            }
            removeCore(playerId);
        }
    }

    private boolean isOfflineProtected(UUID ownerId) {
        if (!requireOnlineOwnerToBreak) {
            return false;
        }

        long lastSeen = data.getLong(playerPath(ownerId) + ".last-seen", 0L);
        if (lastSeen <= 0L) {
            return true;
        }

        long offlineMillis = System.currentTimeMillis() - lastSeen;
        long graceMillis = offlineProtectionGraceSeconds * 1000L;
        return offlineMillis >= graceMillis;
    }

    private CoreBlock getNearbyCore(Block block, int radius) {
        World world = block.getWorld();
        int baseX = block.getX();
        int baseY = block.getY();
        int baseZ = block.getZ();
        for (int x = baseX - radius; x <= baseX + radius; x++) {
            for (int y = baseY - radius; y <= baseY + radius; y++) {
                for (int z = baseZ - radius; z <= baseZ + radius; z++) {
                    CoreBlock core = getCoreBlock(world.getBlockAt(x, y, z));
                    if (core != null) {
                        return core;
                    }
                }
            }
        }
        return null;
    }

    private void alertCoreOwner(CoreBlock core, Player breaker, Block block) {
        Player owner = Bukkit.getPlayer(core.ownerId());
        if (owner == null) {
            return;
        }

        owner.sendTitle(
                color("&cCore Alert"),
                color("&f" + breaker.getName() + " broke a block near your core."),
                5,
                45,
                10);
        send(owner, "&cBlock broken near your core at &e"
                + block.getX() + " " + block.getY() + " " + block.getZ() + "&c.");
    }

    private void showCoreHoldTimer(Player player, int seconds, boolean charging) {
        ChatColor color = charging ? ChatColor.AQUA : ChatColor.RED;
        String state = charging ? "charging" : "holding";
        player.sendActionBar(color + "Core timer " + ChatColor.YELLOW + seconds + "s " + ChatColor.GRAY + state);
    }

    private boolean wouldActivateCoreBeacon(Block placedBlock) {
        if (!isBeaconBaseMaterial(placedBlock.getType())) {
            return false;
        }

        World world = placedBlock.getWorld();
        int x = placedBlock.getX();
        int y = placedBlock.getY();
        int z = placedBlock.getZ();
        for (int level = 1; level <= 4; level++) {
            for (int coreX = x - level; coreX <= x + level; coreX++) {
                for (int coreZ = z - level; coreZ <= z + level; coreZ++) {
                    if (getCoreBlock(world.getBlockAt(coreX, y + level, coreZ)) != null) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasBeaconBaseBelow(Location coreLocation) {
        World world = coreLocation.getWorld();
        if (world == null) {
            return false;
        }

        int coreX = coreLocation.getBlockX();
        int coreY = coreLocation.getBlockY();
        int coreZ = coreLocation.getBlockZ();
        for (int level = 1; level <= 4; level++) {
            int y = coreY - level;
            for (int x = coreX - level; x <= coreX + level; x++) {
                for (int z = coreZ - level; z <= coreZ + level; z++) {
                    if (isBeaconBaseMaterial(world.getBlockAt(x, y, z).getType())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isBeaconBaseMaterial(Material material) {
        return material == Material.IRON_BLOCK
                || material == Material.GOLD_BLOCK
                || material == Material.DIAMOND_BLOCK
                || material == Material.EMERALD_BLOCK
                || material == Material.NETHERITE_BLOCK;
    }

    private CoreBlock getCoreBlock(Block block) {
        if (block.getType() != coreMaterial || !(block.getState() instanceof TileState tileState)) {
            return null;
        }

        PersistentDataContainer container = tileState.getPersistentDataContainer();
        String ownerId = container.get(coreOwnerKey, PersistentDataType.STRING);
        if (ownerId == null) {
            return null;
        }

        String ownerName = container.get(coreOwnerNameKey, PersistentDataType.STRING);
        try {
            return new CoreBlock(UUID.fromString(ownerId), ownerName == null ? "Unknown" : ownerName);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private Location findSafeRespawn(Location coreLocation) {
        if (coreLocation == null || coreLocation.getWorld() == null) {
            return null;
        }

        World world = coreLocation.getWorld();
        int baseX = coreLocation.getBlockX();
        int baseY = coreLocation.getBlockY();
        int baseZ = coreLocation.getBlockZ();

        for (int radius = 1; radius <= respawnSearchRadius; radius++) {
            for (int x = baseX - radius; x <= baseX + radius; x++) {
                for (int y = Math.max(world.getMinHeight() + 1, baseY - radius); y <= Math.min(world.getMaxHeight() - 2, baseY + radius); y++) {
                    for (int z = baseZ - radius; z <= baseZ + radius; z++) {
                        Location location = new Location(world, x + 0.5, y, z + 0.5);
                        if (isSafeRespawn(location)) {
                            location.setYaw(coreLocation.getYaw());
                            location.setPitch(coreLocation.getPitch());
                            return location;
                        }
                    }
                }
            }
        }

        return null;
    }

    private boolean isSafeRespawn(Location location) {
        Block feet = location.getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        Block ground = feet.getRelative(BlockFace.DOWN);
        return !ground.isPassable() && feet.isPassable() && head.isPassable();
    }

    private Location getCoreLocation(UUID playerId) {
        String path = playerPath(playerId) + ".core";
        String worldName = data.getString(path + ".world");
        if (worldName == null) {
            return null;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }

        return new Location(world, data.getInt(path + ".x"), data.getInt(path + ".y"), data.getInt(path + ".z"));
    }

    private void setCoreLocation(UUID playerId, Location location) {
        String path = playerPath(playerId) + ".core";
        data.set(path + ".world", location.getWorld().getName());
        data.set(path + ".x", location.getBlockX());
        data.set(path + ".y", location.getBlockY());
        data.set(path + ".z", location.getBlockZ());
        saveData();
    }

    private void removeCore(UUID playerId) {
        data.set(playerPath(playerId) + ".core", null);
        saveData();
    }

    private void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack stack : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), stack);
        }
    }

    private void giveCoreItemIfMissing(Player player) {
        if (getCoreLocation(player.getUniqueId()) == null && !hasOwnCoreItem(player)) {
            giveOrDrop(player, createCoreItem(player));
        }
    }

    private void dropInventoryForSelfdestruct(Player player) {
        Location location = player.getLocation();
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && !item.getType().isAir() && !isBoundItem(item)) {
                player.getWorld().dropItemNaturally(location, item);
                inventory.setItem(i, null);
            }
        }

        ItemStack[] armor = inventory.getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            ItemStack item = armor[i];
            if (item != null && !item.getType().isAir() && !isBoundItem(item)) {
                player.getWorld().dropItemNaturally(location, item);
                armor[i] = null;
            }
        }
        inventory.setArmorContents(armor);

        ItemStack offhand = inventory.getItemInOffHand();
        if (!offhand.getType().isAir() && !isBoundItem(offhand)) {
            player.getWorld().dropItemNaturally(location, offhand);
            inventory.setItemInOffHand(null);
        }
    }

    private boolean hasOwnCoreItem(Player player) {
        String playerId = player.getUniqueId().toString();
        for (ItemStack item : player.getInventory().getContents()) {
            if (playerId.equals(getCoreItemOwnerId(item))) {
                return true;
            }
        }
        return playerId.equals(getCoreItemOwnerId(player.getInventory().getItemInOffHand()));
    }

    private int getCoreHoldSeconds(UUID playerId) {
        return data.getInt(playerPath(playerId) + ".core-hold-seconds", coreHoldMaxSeconds);
    }

    private void setCoreHoldSeconds(UUID playerId, int seconds) {
        data.set(playerPath(playerId) + ".core-hold-seconds", Math.max(0, Math.min(coreHoldMaxSeconds, seconds)));
    }

    private boolean removeBoundItemsFromDrops(List<ItemStack> drops) {
        boolean removedCoreItem = false;
        for (int i = drops.size() - 1; i >= 0; i--) {
            ItemStack drop = drops.get(i);
            if (!isBoundItem(drop)) {
                continue;
            }

            if (isCoreItem(drop)) {
                removedCoreItem = true;
            }
            drops.remove(i);
        }
        return removedCoreItem;
    }

    private boolean shouldCancelBoundInventoryClick(InventoryClickEvent event) {
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        ItemStack hotbar = null;
        if (event.getHotbarButton() >= 0 && event.getWhoClicked() instanceof Player player) {
            hotbar = player.getInventory().getItem(event.getHotbarButton());
        }

        if (!isBoundItem(current) && !isBoundItem(cursor) && !isBoundItem(hotbar)) {
            return false;
        }

        InventoryAction action = event.getAction();
        if (action == InventoryAction.DROP_ALL_CURSOR
                || action == InventoryAction.DROP_ALL_SLOT
                || action == InventoryAction.DROP_ONE_CURSOR
                || action == InventoryAction.DROP_ONE_SLOT
                || action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || action == InventoryAction.HOTBAR_SWAP) {
            return true;
        }

        if (event.getClickedInventory() != null && event.getClickedInventory().getType() != InventoryType.PLAYER) {
            return isBoundItem(cursor) || isBoundItem(hotbar) || isBoundItem(current);
        }

        return false;
    }

    private boolean isBoundItem(ItemStack item) {
        return isCoreItem(item) || isCorebreaker(item);
    }

    private void loadSettings() {
        coreMaterial = materialFromConfig("core.block-material", Material.BEACON);
        corebreakerMaterial = materialFromConfig("corebreaker.material", Material.NETHERITE_PICKAXE);
        requireOnlineOwnerToBreak = getConfig().getBoolean("core.require-online-owner-to-break", true);
        selfdestructCooldownSeconds = getConfig().getInt("core.selfdestruct-cooldown-seconds", 300);
        respawnSearchRadius = Math.max(1, getConfig().getInt("core.respawn-search-radius", 3));
        giveStartingCorebreaker = getConfig().getBoolean("core.give-starting-corebreaker", true);
        giveStartingCore = getConfig().getBoolean("core.give-starting-core", true);
        offlineProtectionGraceSeconds = Math.max(0, getConfig().getInt("core.offline-protection-grace-seconds", 600));
        offlineCoreExpireDays = Math.max(0, getConfig().getInt("core.offline-core-expire-days", 30));
        coreTeleportDelaySeconds = Math.max(0, getConfig().getInt("core.teleport-delay-seconds", 3));
        coreBreakMiningFatigueSeconds = Math.max(1, getConfig().getInt("core.break-mining-fatigue-seconds", 300));
        coreHoldMaxSeconds = Math.max(1, getConfig().getInt("core.hold-max-seconds", 300));
    }

    private Material materialFromConfig(String path, Material fallback) {
        String value = getConfig().getString(path, fallback.name());
        Material material = Material.matchMaterial(value.toUpperCase(Locale.ROOT));
        if (material == null) {
            getLogger().warning("Invalid material at " + path + ": " + value + ". Using " + fallback.name() + ".");
            return fallback;
        }
        return material;
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            dataFile.getParentFile().mkdirs();
            saveResource("data.yml", false);
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException exception) {
            getLogger().severe("Could not save data.yml: " + exception.getMessage());
        }
    }

    private String playerPath(UUID playerId) {
        return "players." + playerId;
    }

    private void send(Player player, String message) {
        player.sendMessage(color(getConfig().getString("messages.prefix", "&8[&cCore&8] &r") + message));
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private record CoreBlock(UUID ownerId, String ownerName) {
    }
}
