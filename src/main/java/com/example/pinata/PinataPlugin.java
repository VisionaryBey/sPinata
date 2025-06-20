package com.example.pinata;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.Color;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.bstats.bukkit.Metrics;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class PinataPlugin extends JavaPlugin implements Listener {

    // Config dosyaları
    private FileConfiguration config;
    private FileConfiguration dataConfig;
    private File dataFile;
    private File statsFile;
    private FileConfiguration statsConfig;
    
    // Veri yapıları
    private Map<UUID, PinataData> activePinatas;
    private Map<UUID, HashMap<UUID, Double>> damageMap;
    private boolean decentHologramsEnabled;
    private Map<UUID, ArmorStand> headStands;
    private Map<UUID, PlayerStats> playerStats;
    private Economy economy;
    private Map<UUID, Integer> comboMap;
    private Map<UUID, Long> lastHitMap;
    
    // Seçimler
    private EntityType selectedMobType = EntityType.SHEEP;
    private Location selectedLocation = null;
    private PinataType selectedPinataType = PinataType.NORMAL;
    private Map<Location, BukkitRunnable> countdownHolograms = new HashMap<>();
    private Random random = new Random();

    // Hologram eklemek için gerekli değişkenler
    private Map<UUID, ArmorStand> holograms;

    @Override
    public void onEnable() {
        // Config dosyalarını yükle
        saveDefaultConfig();
        config = getConfig();
        
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            saveResource("data.yml", false);
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        
        statsFile = new File(getDataFolder(), "stats.yml");
        if (!statsFile.exists()) {
            saveResource("stats.yml", false);
        }
        statsConfig = YamlConfiguration.loadConfiguration(statsFile);
        
        // Veri yapılarını başlat
        activePinatas = new HashMap<>();
        damageMap = new HashMap<>();
        headStands = new HashMap<>();
        playerStats = new HashMap<>();
        comboMap = new HashMap<>();
        lastHitMap = new HashMap<>();
        holograms = new HashMap<>();
        
        // Vault ekonomisi kurulumu
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            setupEconomy();
        }
        
        // DecentHolograms kontrolü
        decentHologramsEnabled = getServer().getPluginManager().getPlugin("DecentHolograms") != null;
        if (decentHologramsEnabled) {
            getLogger().info("DecentHolograms bulundu - hologram desteği aktif!");
        }
        
        // Event ve komut kayıtları
        getServer().getPluginManager().registerEvents(this, this);
        loadPlayerStats();
        loadPinataLocations();
        startAutoSpawnTask();
        
        // Metrikler
        new Metrics(this, 12345);
        
        getLogger().info("Pinata eklentisi başarıyla etkinleştirildi!");
    }

    @Override
    public void onDisable() {
        // Verileri kaydet
        savePinataLocations();
        savePlayerStats();
        
        // Baş standlarını kaldır
        headStands.values().forEach(ArmorStand::remove);
        headStands.clear();
        
        // Hologram görevlerini temizle
        countdownHolograms.values().forEach(BukkitRunnable::cancel);
        countdownHolograms.clear();
        
        // Hologramı kaldır
        holograms.values().forEach(ArmorStand::remove);
        holograms.clear();

        getLogger().info("Pinata eklentisi başarıyla devre dışı bırakıldı!");
    }

    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    private void loadPlayerStats() {
        if (statsConfig.contains("players")) {
            ConfigurationSection players = statsConfig.getConfigurationSection("players");
            for (String key : players.getKeys(false)) {
                UUID playerId = UUID.fromString(key);
                PlayerStats stats = new PlayerStats(
                    playerId,
                    players.getInt(key + ".totalDamage"),
                    players.getInt(key + ".pinatasDestroyed"),
                    players.getInt(key + ".rewardsEarned"),
                    players.getDouble(key + ".moneyEarned")
                );
                playerStats.put(playerId, stats);
            }
        }
    }

    private void savePlayerStats() {
        statsConfig.set("players", null); // Önceki verileri temizle
        ConfigurationSection players = statsConfig.createSection("players");
        
        for (Map.Entry<UUID, PlayerStats> entry : playerStats.entrySet()) {
            String key = entry.getKey().toString();
            PlayerStats stats = entry.getValue();
            
            players.set(key + ".totalDamage", stats.getTotalDamage());
            players.set(key + ".pinatasDestroyed", stats.getPinatasDestroyed());
            players.set(key + ".rewardsEarned", stats.getRewardsEarned());
            players.set(key + ".moneyEarned", stats.getMoneyEarned());
        }
        
        try {
            statsConfig.save(statsFile);
        } catch (IOException e) {
            getLogger().severe("Oyuncu istatistikleri kaydedilirken hata oluştu: " + e.getMessage());
        }
    }

    private void loadPinataLocations() {
        if (dataConfig.contains("locations")) {
            ConfigurationSection locations = dataConfig.getConfigurationSection("locations");
            for (String key : locations.getKeys(false)) {
                World world = getServer().getWorld(locations.getString(key + ".world"));
                if (world == null) continue;
                
                Location loc = new Location(
                    world,
                    locations.getDouble(key + ".x"),
                    locations.getDouble(key + ".y"),
                    locations.getDouble(key + ".z"),
                    (float) locations.getDouble(key + ".yaw", 0),
                    (float) locations.getDouble(key + ".pitch", 0)
                );
                
                EntityType type = EntityType.valueOf(locations.getString(key + ".type", "SHEEP"));
                boolean autoSpawn = locations.getBoolean(key + ".autoSpawn", false);
                
                if (autoSpawn) {
                    spawnPinata(loc, type, PinataType.NORMAL, true);
                }
            }
        }
    }

    private void savePinataLocations() {
        try {
            // Mevcut konumları temizle
            dataConfig.set("locations", null);
            ConfigurationSection locations = dataConfig.createSection("locations");
            
            // Aktif pinataları kaydet
            int i = 0;
            for (PinataData data : activePinatas.values()) {
                String key = "location_" + i++;
                Location loc = data.getLocation();
                
                locations.set(key + ".world", loc.getWorld().getName());
                locations.set(key + ".x", loc.getX());
                locations.set(key + ".y", loc.getY());
                locations.set(key + ".z", loc.getZ());
                locations.set(key + ".yaw", loc.getYaw());
                locations.set(key + ".pitch", loc.getPitch());
                locations.set(key + ".type", data.getEntityType().name());
                locations.set(key + ".autoSpawn", true);
            }
            
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Pinata konumları kaydedilirken hata oluştu: " + e.getMessage());
        }
    }

    private void startAutoSpawnTask() {
        if (config.getBoolean("auto-spawn.enabled", false)) {
            long delay = config.getLong("auto-spawn.delay", 86400) * 20;
            new BukkitRunnable() {
                @Override
                public void run() {
                    spawnAllPinatas();
                }
            }.runTaskTimer(this, delay, delay);
        }
    }

    private void spawnAllPinatas() {
        if (dataConfig.contains("locations")) {
            ConfigurationSection locations = dataConfig.getConfigurationSection("locations");
            for (String key : locations.getKeys(false)) {
                World world = getServer().getWorld(locations.getString(key + ".world"));
                if (world == null) continue;
                
                Location loc = new Location(
                    world,
                    locations.getDouble(key + ".x"),
                    locations.getDouble(key + ".y"),
                    locations.getDouble(key + ".z"),
                    (float) locations.getDouble(key + ".yaw", 0),
                    (float) locations.getDouble(key + ".pitch", 0)
                );
                
                EntityType type = EntityType.valueOf(locations.getString(key + ".type", "SHEEP"));
                spawnPinata(loc, type, PinataType.NORMAL, true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPinataDamage(EntityDamageByEntityEvent event) {
        if (!activePinatas.containsKey(event.getEntity().getUniqueId())) return;

        if (event.getDamager() instanceof Player) {
            Player player = (Player) event.getDamager();
            UUID pinataId = event.getEntity().getUniqueId();
            LivingEntity pinata = (LivingEntity) event.getEntity();

            // Combo sistemi
            long currentTime = System.currentTimeMillis();
            long lastHitTime = lastHitMap.getOrDefault(player.getUniqueId(), 0L);
            int combo = comboMap.getOrDefault(player.getUniqueId(), 0);
            
            if (currentTime - lastHitTime < 2000) { // 2 saniye içinde vurdu
                combo++;
            } else {
                combo = 1;
            }
            
            comboMap.put(player.getUniqueId(), combo);
            lastHitMap.put(player.getUniqueId(), currentTime);
            
            // Kritik vuruş kontrolü
            boolean isCritical = player.getFallDistance() > 0 && 
                !player.isOnGround() && !player.isInsideVehicle();
            
            // Hasar hesaplama (combo bonusu ile)
            double finalDamage = event.getFinalDamage() * (1 + (combo * 0.05));
            if (isCritical) {
                finalDamage *= 1.5; // Kritik vuruş bonusu
                spawnCriticalParticles(pinata);
            }

            // Hasar kaydı
            if (!damageMap.containsKey(pinataId)) {
                damageMap.put(pinataId, new HashMap<>());
            }

            HashMap<UUID, Double> playerDamages = damageMap.get(pinataId);
            double currentDamage = playerDamages.getOrDefault(player.getUniqueId(), 0.0);
            double newDamage = currentDamage + finalDamage;
            playerDamages.put(player.getUniqueId(), newDamage);

            // İstatistik güncelleme
            PlayerStats stats = playerStats.computeIfAbsent(player.getUniqueId(), 
                k -> new PlayerStats(player.getUniqueId(), 0, 0, 0, 0));
            stats.addTotalDamage(finalDamage);

            // Can güncelleme
            double newHealth = Math.max(0, pinata.getHealth() - finalDamage);
            pinata.setHealth(newHealth);

            // İsim tag'ını güncelle
            updateNameTag(pinata, player, newDamage, isCritical, combo);

            // Ses ve partikül efektleri
            player.playSound(event.getEntity().getLocation(), Sound.ENTITY_SLIME_ATTACK, 1.0f, 1.0f);
            spawnDamageParticles(pinata, finalDamage, isCritical);

            // Pinata hareket mekaniği (sürekli hareket ve dönüşüm)
            movePinataRandomly(pinata);
            tryTransformPinata(pinata, newHealth);

            // Ölüm kontrolü
            if (newHealth <= 0) {
                event.setCancelled(true);
                onPinataDeath(new EntityDeathEvent(pinata, new ArrayList<>()));
            }
            
            // Combo mesajı
            if (combo > 3) {
                sendActionBar(player, ChatColor.YELLOW + "Combo: " + combo + "x! " + 
                    ChatColor.GOLD + "+" + (int)((combo * 5)) + "% Hasar");
            }
        }
    }

    private void sendActionBar(Player player, String message) {
        try {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message));
        } catch (Exception e) {
            // Eski sürümler için fallback
            player.sendMessage(message);
        }
    }

    private void tryTransformPinata(LivingEntity pinata, double currentHealth) {
        double maxHealth = pinata.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
        double healthPercentage = currentHealth / maxHealth;

        if (random.nextDouble() < 0.2 && healthPercentage < 0.5) {
            if (pinata instanceof Sheep || pinata instanceof Cow) {
                Ageable mob = (Ageable) pinata;
                if (mob.isAdult()) {
                    mob.setBaby();
                    mob.getWorld().spawnParticle(Particle.HEART, mob.getLocation(), 10);
                    mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 2.0f);

                    // Küçülürken zıplama ve efekt bırakma
                    mob.setVelocity(new Vector(0, 0.5, 0));
                    spawnCircularEffect(mob.getLocation(), Particle.CLOUD);
                    
                    // 2 saniye sonra tekrar büyüme
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            mob.setAdult();
                            mob.getWorld().spawnParticle(Particle.VILLAGER_HAPPY, mob.getLocation(), 10);
                            mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);
                        }
                    }.runTaskLater(this, 40L); // 2 saniye (40 tick)
                }
            }
        }
    }

    private void spawnCircularEffect(Location location, Particle cloud) {
        if (location.getWorld() == null) return;
        // Dairesel partikül efekti
        if (cloud == null) {
            cloud = Particle.CLOUD; // Varsayılan olarak bulut partikülü
        }
        // Daire etrafında partiküller oluştur
        if (location.getWorld() == null) return;
        if (location.getWorld().getEnvironment() == World.Environment.NETHER) {
            cloud = Particle.FLAME; // Nether için alev partikülü
        } else if (location.getWorld().getEnvironment() == World.Environment.THE_END) {
            cloud = Particle.END_ROD; // The End için end rod partikülü
        }
        
        double radius = 1.0;
        int particles = 20;
        for (int i = 0; i < particles; i++) {
            double angle = 2 * Math.PI * i / particles;
            double x = radius * Math.cos(angle);
            double z = radius * Math.sin(angle);
            location.getWorld().spawnParticle(cloud, 
                location.clone().add(x, 0, z), 1, 0, 0, 0, 0);
        }
       
    }

    private void spawnCriticalParticles(LivingEntity pinata) {
        pinata.getWorld().spawnParticle(Particle.CRIT, 
            pinata.getLocation().add(0, 1, 0), 30, 0.5, 0.5, 0.5, 0.5);
        pinata.getWorld().playSound(pinata.getLocation(), 
            Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.0f);
    }

    private void updateNameTag(LivingEntity pinata, Player player, double damage, boolean isCritical, int combo) {
        String name = ChatColor.translateAlternateColorCodes('&',
            String.format("&a%.1f/%.1f HP &7- &e%s: &c%.1f %s%s",
                pinata.getHealth(),
                pinata.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue(),
                player.getName(),
                damage,
                isCritical ? "&6✧" : "",
                combo > 3 ? " &e" + combo + "x" : ""));
        pinata.setCustomName(name);
        pinata.setCustomNameVisible(true);

        // Hasar göstergesini küçült ve can/armor barının üstüne yerleştir
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(
            ChatColor.RED + "Hasar: " + ChatColor.WHITE + String.format("%.1f", damage) +
            (combo > 3 ? ChatColor.YELLOW + " Combo: " + combo + "x!" : "")
        ));
    }

    private void movePinataRandomly(LivingEntity pinata) {
        Random random = new Random();
        Vector velocity = new Vector(
            (random.nextDouble() - 0.5) * 0.3,
            0.2,
            (random.nextDouble() - 0.5) * 0.3
        );
        pinata.setVelocity(velocity);
        
        // Uçan moblar için ekstra hareket
        if (pinata instanceof Flying) {
            velocity.setY((random.nextDouble() - 0.3) * 0.5);
        }
        
        // Su mobları için su partikülleri
        if (pinata.getType() == EntityType.DOLPHIN || pinata.getType() == EntityType.AXOLOTL) {
            pinata.getWorld().spawnParticle(Particle.WATER_SPLASH, 
                pinata.getLocation(), 5, 0.5, 0.5, 0.5);
        }
    }

    private void spawnDamageParticles(LivingEntity pinata, double damage, boolean isCritical) {
        Particle particle;
        if (damage > 10) {
            particle = isCritical ? Particle.FIREWORKS_SPARK : Particle.CRIT_MAGIC;
        } else if (damage > 5) {
            particle = isCritical ? Particle.HEART : Particle.VILLAGER_HAPPY;
        } else {
            particle = Particle.VILLAGER_HAPPY;
        }
        
        int count = (int) Math.min(damage * 2, 30);
        pinata.getWorld().spawnParticle(particle, 
            pinata.getLocation().add(0, 1, 0), count, 0.5, 0.5, 0.5, 0.1);
        
        // Renkli partiküller (koyunlar için)
        if (pinata instanceof Sheep) {
            Sheep sheep = (Sheep) pinata;
            if (sheep.getColor() != null) {
                Particle.DustOptions dust = new Particle.DustOptions(
                    Color.fromRGB(getColorFromDye(sheep.getColor())), 1.5f);
                pinata.getWorld().spawnParticle(Particle.REDSTONE, 
                    pinata.getLocation().add(0, 1, 0), 10, 0.5, 0.5, 0.5, dust);
            }
        }
    }

    private int getColorFromDye(DyeColor color) {
        switch (color) {
            case WHITE: return 0xF9FFFE;
            case ORANGE: return 0xF9801D;
            case MAGENTA: return 0xC74EBD;
            case LIGHT_BLUE: return 0x3AB3DA;
            case YELLOW: return 0xFED83D;
            case LIME: return 0x80C71F;
            case PINK: return 0xF38BAA;
            case GRAY: return 0x474F52;
            case LIGHT_GRAY: return 0x9D9D97;
            case CYAN: return 0x169C9C;
            case PURPLE: return 0x8932B8;
            case BLUE: return 0x3C44AA;
            case BROWN: return 0x835432;
            case GREEN: return 0x5E7C16;
            case RED: return 0xB02E26;
            case BLACK: return 0x1D1D21;
            
            default: return 0xFFFFFF;
        }
    }

    @EventHandler
    public void onPinataDeath(EntityDeathEvent event) {
        if (!activePinatas.containsKey(event.getEntity().getUniqueId())) return;

        LivingEntity pinata = (LivingEntity) event.getEntity();
        PinataData data = activePinatas.get(pinata.getUniqueId());
        Location loc = pinata.getLocation();

        // Olayı iptal et ve temizle
        ((Cancellable) event).setCancelled(true);
        pinata.remove();
        event.getDrops().clear();
        event.setDroppedExp(0);

        // Hologramı kaldır
        removeHologram(pinata.getUniqueId());

        // Patlama efektleri
        spawnExplosionEffects(loc, data.getPinataType());

        // Ödül dağıtımı
        if (damageMap.containsKey(pinata.getUniqueId())) {
            HashMap<UUID, Double> damages = damageMap.get(pinata.getUniqueId());
            
            // Hasar sıralaması
            List<Map.Entry<UUID, Double>> sorted = damages.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .collect(Collectors.toList());
            
            // En iyi oyuncuları duyur
            announceTopPlayers(sorted, loc);
            
            // Ödülleri dağıt
            for (int i = 0; i < sorted.size(); i++) {
                Player player = getServer().getPlayer(sorted.get(i).getKey());
                if (player != null && player.isOnline()) {
                    distributeRewards(player, i + 1, data.getPinataType());
                }
            }
        }
        
        // Baş standını kaldır
        removeHeadStand(pinata.getUniqueId());
        
        // Veri yapılarından kaldır
        activePinatas.remove(pinata.getUniqueId());
        damageMap.remove(pinata.getUniqueId());
        
        // Yeniden doğma kontrolü
        if (data.shouldRespawn()) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    spawnPinata(data.getLocation(), data.getEntityType(), data.getPinataType(), true);
                }
            }.runTaskLater(this, data.getRespawnDelay() * 20L);
        }
    }

    private void removeHologram(UUID pinataId) {
        if (holograms.containsKey(pinataId)) {
            ArmorStand hologram = holograms.remove(pinataId);
            if (hologram != null) {
                hologram.remove();
            }
        }
    }

    private void distributeRewards(Player player, int position, PinataType pinataType) {
        PlayerStats stats = playerStats.computeIfAbsent(player.getUniqueId(), 
            k -> new PlayerStats(player.getUniqueId(), 0, 0, 0, 0));
        stats.incrementPinatasDestroyed();
        stats.incrementRewardsEarned();
        
        String tierPath = "pinata-types." + pinataType.name().toLowerCase() + ".position-rewards." + position;
        if (config.contains(tierPath)) {
            ItemStack reward = config.getItemStack(tierPath);
            if (reward != null) {
                player.getInventory().addItem(reward.clone());
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', 
                    config.getString("messages.position-reward", "&a#%position%. sırada olduğun için ödül kazandın!"))
                    .replace("%position%", String.valueOf(position)));
                stats.addRewardsEarned(1);
            }
        }
        
        if (position == 1) {
            String topRewardPath = "pinata-types." + pinataType.name().toLowerCase() + ".top-reward";
            if (config.contains(topRewardPath)) {
                ItemStack reward = config.getItemStack(topRewardPath);
                if (reward != null) {
                    player.getInventory().addItem(reward.clone());
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', 
                        config.getString("messages.top-reward", "&aBirinci olduğun için süper ödül kazandın!")));
                    stats.addRewardsEarned(1);
                }
            }
            
            if (economy != null) {
                double moneyReward = config.getDouble("pinata-types." + pinataType.name().toLowerCase() + ".money-reward", 100);
                economy.depositPlayer(player, moneyReward);
                player.sendMessage(ChatColor.GREEN + "+$" + moneyReward + " kazandınız!");
                stats.addMoneyEarned(moneyReward);
            }
        }
        
        List<String> rewards = config.getStringList("pinata-types." + pinataType.name().toLowerCase() + ".rewards");
        if (!rewards.isEmpty()) {
            String rewardCmd = rewards.get(new Random().nextInt(rewards.size()))
                .replace("%player%", player.getName());
            getServer().dispatchCommand(getServer().getConsoleSender(), rewardCmd);
            
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', 
                config.getString("messages.reward", "&aPinatadan ödül kazandın!")));
            stats.addRewardsEarned(1);
        }
    }

    private void removeHeadStand(UUID pinataId) {
        if (headStands.containsKey(pinataId)) {
            ArmorStand stand = headStands.remove(pinataId);
            if (stand != null) {
                stand.remove();
            }
        }
    }

    private void spawnExplosionEffects(Location loc, PinataType pinataType) {
        switch (pinataType) {
            case NORMAL:
                loc.getWorld().spawnParticle(Particle.EXPLOSION_LARGE, loc, 5);
                loc.getWorld().spawnParticle(Particle.FIREWORKS_SPARK, loc, 100, 1, 1, 1, 0.5);
                break;
            case ELITE:
                loc.getWorld().spawnParticle(Particle.EXPLOSION_HUGE, loc, 3);
                loc.getWorld().spawnParticle(Particle.FLAME, loc, 150, 1, 1, 1, 0.3);
                loc.getWorld().spawnParticle(Particle.SMOKE_LARGE, loc, 50, 1, 1, 1, 0.2);
                break;
            case LEGENDARY:
                loc.getWorld().spawnParticle(Particle.DRAGON_BREATH, loc, 200, 1, 1, 1, 0.5);
                loc.getWorld().spawnParticle(Particle.END_ROD, loc, 100, 1, 1, 1, 0.3);
                loc.getWorld().spawnParticle(Particle.FIREWORKS_SPARK, loc, 200, 1, 1, 1, 0.5);
                break;
        }
        
        loc.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);
        loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
    }

    private void announceTopPlayers(List<Map.Entry<UUID, Double>> sorted, Location loc) {
        if (config.getBoolean("announce-top-players", true)) {
            String announcement = ChatColor.translateAlternateColorCodes('&', 
                config.getString("messages.top-announcement", "&6Pinata Top Hasar:"));
            
            for (int i = 0; i < Math.min(sorted.size(), 3); i++) {
                Player player = getServer().getPlayer(sorted.get(i).getKey());
                if (player != null) {
                    String playerLine = ChatColor.translateAlternateColorCodes('&', 
                        config.getString("messages.top-format", "&e#%position% &a%player% &7- &e%damage%"))
                        .replace("%position%", String.valueOf(i + 1))
                        .replace("%player%", player.getName())
                        .replace("%damage%", String.format("%.1f", sorted.get(i).getValue()));
                    
                    loc.getWorld().getPlayers().forEach(p -> p.sendMessage(announcement + "\n" + playerLine));
                }
            }
        }
    }

    public boolean spawnPinata(Location loc, EntityType type, PinataType pinataType, boolean loadFromConfig) {
        if (loc.getWorld() == null) return false;

        Entity entity = loc.getWorld().spawnEntity(loc, type);

        if (entity instanceof LivingEntity) {
            LivingEntity livingEntity = (LivingEntity) entity;
            
            // Temel ayarlar
            livingEntity.setAI(true); // Sürekli hareket için AI aktif
            livingEntity.setInvulnerable(false);
            livingEntity.setSilent(true);
            
            // Can ayarları
            double health = loadFromConfig ? 
                config.getDouble("pinata-types." + pinataType.name().toLowerCase() + ".health", 100.0) :
                activePinatas.containsKey(entity.getUniqueId()) ? 
                    activePinatas.get(entity.getUniqueId()).getHealth() : 
                    100.0;
            
            // Oyuncu sayısına göre can çarpanı
            int onlinePlayers = Math.max(1, Bukkit.getOnlinePlayers().size());
            health *= (1 + (onlinePlayers * 0.1));
            
            livingEntity.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(health);
            livingEntity.setHealth(health);
            
            // İsim tag'ını güncelle
            livingEntity.setCustomNameVisible(true);
            
            // Efektler ve görsel öğeler
            applyPinataEffects(livingEntity, pinataType);
            spawnMobHead(livingEntity);
            
            // Renkli koyun ve lamalar
            if (livingEntity instanceof Sheep) {
                Sheep sheep = (Sheep) livingEntity;
                sheep.setColor(DyeColor.values()[random.nextInt(DyeColor.values().length)]);
                sheep.setSheared(false);
            } else if (livingEntity instanceof Llama) {
                Llama llama = (Llama) livingEntity;
                llama.setColor(Llama.Color.values()[random.nextInt(Llama.Color.values().length)]);
            }
            
            // Yeniden doğma ayarları
            boolean respawn = config.getBoolean("pinata.respawn.enabled", false);
            int respawnDelay = config.getInt("pinata.respawn.delay", 60);
            activePinatas.put(entity.getUniqueId(), 
                new PinataData(loc, type, health, respawn, respawnDelay, pinataType));
            
            // Hologram ekle
            spawnHologram(livingEntity);
            
            // Duyuru mesajı
            String announcement = ChatColor.translateAlternateColorCodes('&',
                config.getString("messages.pinata-spawn", "&6Bir &e%type% &6pinata doğdu!")
                    .replace("%type%", pinataType.getDisplayName()));
            Bukkit.broadcastMessage(announcement);

            return true;
        }
        
        return false;
    }

    private void applyPinataEffects(LivingEntity pinata, PinataType pinataType) {
        switch (pinataType) {
            case ELITE:
                pinata.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, Integer.MAX_VALUE, 1));
                pinata.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0));
                pinata.getWorld().spawnParticle(Particle.ENCHANTMENT_TABLE, pinata.getLocation(), 30, 1, 1, 1);
                break;
            case LEGENDARY:
                pinata.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, Integer.MAX_VALUE, 2));
                pinata.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, Integer.MAX_VALUE, 0));
                pinata.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, Integer.MAX_VALUE, 0));
                pinata.getWorld().spawnParticle(Particle.PORTAL, pinata.getLocation(), 50, 1, 1, 1);
                break;
            default:
                break;
        }
        
        // Tüm pinatalara uygulanan efektler
        pinata.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 1));
    }

    private void applyPinataAbilities(LivingEntity pinata, PinataType pinataType) {
        switch (pinataType) {
            case NORMAL:
                // Normal pinatalar için özel yetenek yok
                break;
            case ELITE:
                // Elit pinatalar için alan hasarı yeteneği
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (!pinata.isValid()) {
                            cancel();
                            return;
                        }
                        pinata.getWorld().spawnParticle(Particle.EXPLOSION_NORMAL, pinata.getLocation(), 10, 1, 1, 1, 0.1);
                        pinata.getWorld().getNearbyEntities(pinata.getLocation(), 3, 3, 3).stream()
                            .filter(e -> e instanceof Player)
                            .forEach(e -> ((Player) e).damage(2.0, pinata));
                    }
                }.runTaskTimer(this, 0, 100); // Her 5 saniyede bir
                break;
            case LEGENDARY:
                // Efsanevi pinatalar için ışınlanma yeteneği
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (!pinata.isValid()) {
                            cancel();
                            return;
                        }
                        Location randomLocation = pinata.getLocation().add(
                            (random.nextDouble() - 0.5) * 10,
                            0,
                            (random.nextDouble() - 0.5) * 10
                        );
                        if (randomLocation.getWorld() != null && randomLocation.getBlock().isPassable()) {
                            pinata.teleport(randomLocation);
                            pinata.getWorld().spawnParticle(Particle.PORTAL, randomLocation, 20, 1, 1, 1, 0.1);
                            pinata.getWorld().playSound(randomLocation, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
                        }
                    }
                }.runTaskTimer(this, 0, 200); // Her 10 saniyede bir
                break;
        }
    }

    private void spawnMobHead(LivingEntity entity) {
        if (headStands.containsKey(entity.getUniqueId())) {
            headStands.get(entity.getUniqueId()).remove();
        }
        
        ArmorStand headStand = entity.getWorld().spawn(entity.getLocation().add(0, 1.5, 0), ArmorStand.class);
        headStand.setGravity(false);
        headStand.setVisible(false);
        headStand.setSmall(true);
        headStand.setInvulnerable(true);
        headStand.setCollidable(false);
        headStand.setMarker(true);
        
        ItemStack skull = getMobHead(entity.getType());
        if (skull != null) {
            headStand.getEquipment().setHelmet(skull);
        }
        
        headStands.put(entity.getUniqueId(), headStand);
        
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!entity.isValid() || !headStand.isValid()) {
                    cancel();
                    return;
                }
                
                headStand.teleport(entity.getLocation().add(0, 1.5, 0));
                
                // Dönen kafa efekti
                Location loc = headStand.getLocation();
                loc.setYaw(loc.getYaw() + 10);
                headStand.teleport(loc);
            }
        }.runTaskTimer(this, 0, 1);
    }

    private ItemStack getMobHead(EntityType type) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();

        switch (type) {
            case SHEEP: meta.setOwner("MHF_Sheep"); break;
            case COW: meta.setOwner("MHF_Cow"); break;
            case PIG: meta.setOwner("MHF_Pig"); break;
            case CHICKEN: meta.setOwner("MHF_Chicken"); break;
            case ZOMBIE: meta.setOwner("MHF_Zombie"); break;
            case SKELETON: meta.setOwner("MHF_Skeleton"); break;
            case CREEPER: meta.setOwner("MHF_Creeper"); break;
            case ENDERMAN: meta.setOwner("MHF_Enderman"); break;
            case SPIDER: meta.setOwner("MHF_Spider"); break;
            case WOLF: meta.setOwner("MHF_Wolf"); break;
            case FROG: meta.setOwner("MHF_Frog"); break;
            case ALLAY: meta.setOwner("MHF_Allay"); break;
            case WARDEN: meta.setOwner("MHF_Warden"); break;
            case AXOLOTL: meta.setOwner("MHF_Axolotl"); break;
            case BEE: meta.setOwner("MHF_Bee"); break;
            case CAT: meta.setOwner("MHF_Ocelot"); break;
            case DOLPHIN: meta.setOwner("MHF_Dolphin"); break;
            case FOX: meta.setOwner("MHF_Fox"); break;
            case GOAT: meta.setOwner("MHF_Goat"); break;
            case PANDA: meta.setOwner("MHF_Panda"); break;
            case PARROT: meta.setOwner("MHF_Parrot"); break;
            case RABBIT: meta.setOwner("MHF_Rabbit"); break;
            case TURTLE: meta.setOwner("MHF_Turtle"); break;
            case HORSE: meta.setOwner("MHF_Horse"); break;
            case LLAMA: meta.setOwner("MHF_Llama"); break;
            default: return null;
        }

        skull.setItemMeta(meta);
        return skull;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("pinata")) {
            if (!sender.hasPermission("pinata.admin")) {
                sender.sendMessage(ChatColor.RED + "Bu komutu kullanma yetkiniz yok!");
                return true;
            }
            
            if (args.length == 0) {
                if (sender instanceof Player) {
                    openMainGUI((Player) sender);
                } else {
                    sendHelp(sender);
                }
                return true;
            }
            
            switch (args[0].toLowerCase()) {
                case "spawn":
                    handleSpawnCommand(sender, args);
                    break;
                    
                case "addlocation":
                    handleAddLocationCommand(sender, args);
                    break;
                    
                case "listlocations":
                    handleListLocationsCommand(sender);
                    break;
                    
                case "start":
                    handleStartCommand(sender);
                    break;
                    
                case "reload":
                    handleReloadCommand(sender);
                    break;
                    
                case "stats":
                    handleStatsCommand(sender, args);
                    break;
                    
                case "gui":
                    if (sender instanceof Player) {
                        openMainGUI((Player) sender);
                    } else {
                        sender.sendMessage(ChatColor.RED + "Bu komut sadece oyuncular tarafından kullanılabilir!");
                    }
                    break;
                    
                default:
                    sender.sendMessage(ChatColor.RED + "Bilinmeyen komut!");
                    sendHelp(sender);
            }
            
            return true;
        }
        return false;
    }

    private void openMainGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 36, ChatColor.BLUE + "Pinata Kontrol Paneli");
        
        // Mob seçme butonu
        ItemStack mobSelect = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta mobMeta = (SkullMeta) mobSelect.getItemMeta();
        mobMeta.setOwner(getMobHeadOwner(selectedMobType));
        mobMeta.setDisplayName(ChatColor.GREEN + "Mob Seç");
        mobMeta.setLore(Arrays.asList(
            ChatColor.GRAY + "Mevcut Seçim: " + ChatColor.YELLOW + selectedMobType.name(),
            "",
            ChatColor.GRAY + "Tıklayarak mob türünü değiştir"
        ));
        mobSelect.setItemMeta(mobMeta);
        gui.setItem(10, mobSelect);
        
        // Pinata türü butonu
        ItemStack typeSelect = new ItemStack(Material.NETHER_STAR);
        ItemMeta typeMeta = typeSelect.getItemMeta();
        typeMeta.setDisplayName(ChatColor.GREEN + "Pinata Türü");
        typeMeta.setLore(Arrays.asList(
            ChatColor.GRAY + "Mevcut Seçim: " + ChatColor.YELLOW + selectedPinataType.getDisplayName(),
            "",
            ChatColor.GRAY + "Tıklayarak pinata türünü değiştir"
        ));
        typeSelect.setItemMeta(typeMeta);
        gui.setItem(12, typeSelect);
        
        // Konum ayarlama butonu
        ItemStack locationItem = new ItemStack(Material.COMPASS);
        ItemMeta locationMeta = locationItem.getItemMeta();
        locationMeta.setDisplayName(ChatColor.GREEN + "Konum Ayarla");
        locationMeta.setLore(Arrays.asList(
            ChatColor.GRAY + "Mevcut konum: " + (selectedLocation != null ? 
                ChatColor.YELLOW + selectedLocation.getWorld().getName() + ", " + 
                selectedLocation.getBlockX() + ", " + 
                selectedLocation.getBlockY() + ", " + 
                selectedLocation.getBlockZ() : ChatColor.RED + "Ayarlanmadı"),
            "",
            ChatColor.GRAY + "Tıklayarak baktığın yeri konum olarak ayarla"
        ));
        locationItem.setItemMeta(locationMeta);
        gui.setItem(14, locationItem);
        
        // Geri sayım ayarları butonu
        ItemStack countdownItem = new ItemStack(Material.CLOCK);
        ItemMeta countdownMeta = countdownItem.getItemMeta();
        countdownMeta.setDisplayName(ChatColor.GREEN + "Geri Sayım Ayarları");
        countdownMeta.setLore(Arrays.asList(
            ChatColor.GRAY + "Geri sayım etkin: " + (config.getBoolean("countdown.enabled", true) ? 
                ChatColor.GREEN + "Açık" : ChatColor.RED + "Kapalı"),
            "",
            ChatColor.GRAY + "Sol tık: Aç/Kapat",
            ChatColor.GRAY + "Sağ tık: Süreyi değiştir"
        ));
        countdownItem.setItemMeta(countdownMeta);
        gui.setItem(16, countdownItem);
        
        // Pinata başlatma butonu
        ItemStack startItem = new ItemStack(Material.LIME_DYE);
        ItemMeta startMeta = startItem.getItemMeta();
        startMeta.setDisplayName(ChatColor.GREEN + "Pinata Başlat");
        startMeta.setLore(Collections.singletonList(
            ChatColor.GRAY + "Tıklayarak pinata etkinliğini başlat"
        ));
        startItem.setItemMeta(startMeta);
        gui.setItem(22, startItem);
        
        // İstatistikler butonu
        ItemStack statsItem = new ItemStack(Material.BOOK);
        ItemMeta statsMeta = statsItem.getItemMeta();
        statsMeta.setDisplayName(ChatColor.GREEN + "İstatistikler");
        statsMeta.setLore(Collections.singletonList(
            ChatColor.GRAY + "Tıklayarak istatistikleri görüntüle"
        ));
        statsItem.setItemMeta(statsMeta);
        gui.setItem(24, statsItem);
        
        // Kapatma butonu
        ItemStack closeItem = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = closeItem.getItemMeta();
        closeMeta.setDisplayName(ChatColor.RED + "Kapat");
        closeItem.setItemMeta(closeMeta);
        gui.setItem(31, closeItem);
        
        player.openInventory(gui);
    }

    private void openMobSelectionGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.BLUE + "Mob Seç");

        // Daha hoş moblar ekleniyor
        List<EntityType> mobs = Arrays.asList(
            EntityType.SHEEP, EntityType.COW, EntityType.PIG, EntityType.CHICKEN,
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CREEPER, EntityType.ENDERMAN,
            EntityType.SPIDER, EntityType.WOLF, EntityType.FROG, EntityType.ALLAY,
            EntityType.WARDEN, EntityType.AXOLOTL, EntityType.BEE, EntityType.CAT,
            EntityType.DOLPHIN, EntityType.FOX, EntityType.GOAT, EntityType.PANDA,
            EntityType.PARROT, EntityType.RABBIT, EntityType.TURTLE, EntityType.HORSE,
            EntityType.LLAMA
        );

        for (EntityType mob : mobs) {
            ItemStack mobItem = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta mobMeta = (SkullMeta) mobItem.getItemMeta();
            mobMeta.setOwner(getMobHeadOwner(mob));
            mobMeta.setDisplayName(ChatColor.GREEN + mob.name());
            mobItem.setItemMeta(mobMeta);
            gui.addItem(mobItem);
        }

        ItemStack backItem = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backItem.getItemMeta();
        backMeta.setDisplayName(ChatColor.GREEN + "Geri");
        backItem.setItemMeta(backMeta);
        gui.setItem(53, backItem);

        player.openInventory(gui);
    }

    private void openPinataTypeSelectionGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 9, ChatColor.BLUE + "Pinata Türü Seç");
        
        for (PinataType type : PinataType.values()) {
            ItemStack item = new ItemStack(type.getIcon());
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(type.getDisplayColor() + type.getDisplayName());
            meta.setLore(Arrays.asList(
                ChatColor.GRAY + "Can: " + ChatColor.RED + config.getDouble("pinata-types." + type.name().toLowerCase() + ".health", 100),
                ChatColor.GRAY + "Ödül Çarpanı: " + ChatColor.GOLD + config.getDouble("pinata-types." + type.name().toLowerCase() + ".reward-multiplier", 1.0)
            ));
            item.setItemMeta(meta);
            gui.addItem(item);
        }
        
        ItemStack backItem = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backItem.getItemMeta();
        backMeta.setDisplayName(ChatColor.GREEN + "Geri");
        backItem.setItemMeta(backMeta);
        gui.setItem(8, backItem);
        
        player.openInventory(gui);
    }

    private void openStatsGUI(Player player) {
        PlayerStats stats = playerStats.getOrDefault(player.getUniqueId(), new PlayerStats(player.getUniqueId(), 0, 0, 0, 0));
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.BLUE + "Pinata İstatistikleri");
        
        ItemStack damageItem = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta damageMeta = damageItem.getItemMeta();
        damageMeta.setDisplayName(ChatColor.GREEN + "Toplam Hasar");
        damageMeta.setLore(Collections.singletonList(
            ChatColor.GRAY + "Verdiğin toplam hasar: " + ChatColor.RED + stats.getTotalDamage()
        ));
        damageItem.setItemMeta(damageMeta);
        gui.setItem(10, damageItem);
        
        ItemStack destroyedItem = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta destroyedMeta = destroyedItem.getItemMeta();
        destroyedMeta.setDisplayName(ChatColor.GREEN + "Yıkılan Pinatalar");
        destroyedMeta.setLore(Collections.singletonList(
            ChatColor.GRAY + "Yıktığın pinata sayısı: " + ChatColor.GOLD + stats.getPinatasDestroyed()
        ));
        destroyedItem.setItemMeta(destroyedMeta);
        gui.setItem(12, destroyedItem);
        
        ItemStack rewardsItem = new ItemStack(Material.CHEST);
        ItemMeta rewardsMeta = rewardsItem.getItemMeta();
        rewardsMeta.setDisplayName(ChatColor.GREEN + "Kazanılan Ödüller");
        rewardsMeta.setLore(Collections.singletonList(
            ChatColor.GRAY + "Kazandığın ödül sayısı: " + ChatColor.GOLD + stats.getRewardsEarned()
        ));
        rewardsItem.setItemMeta(rewardsMeta);
        gui.setItem(14, rewardsItem);
        
        ItemStack moneyItem = new ItemStack(Material.GOLD_INGOT);
        ItemMeta moneyMeta = moneyItem.getItemMeta();
        moneyMeta.setDisplayName(ChatColor.GREEN + "Kazanılan Para");
        moneyMeta.setLore(Collections.singletonList(
            ChatColor.GRAY + "Kazandığın toplam para: " + ChatColor.GOLD + stats.getMoneyEarned()
        ));
        moneyItem.setItemMeta(moneyMeta);
        gui.setItem(16, moneyItem);
        
        ItemStack backItem = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backItem.getItemMeta();
        backMeta.setDisplayName(ChatColor.GREEN + "Geri");
        backItem.setItemMeta(backMeta);
        gui.setItem(22, backItem);
        
        player.openInventory(gui);
    }

    private void openTimeSettingGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.BLUE + "Zaman Ayarları");

        // Otomatik spawn ayarı
        ItemStack autoSpawnItem = new ItemStack(Material.CLOCK);
        ItemMeta autoSpawnMeta = autoSpawnItem.getItemMeta();
        autoSpawnMeta.setDisplayName(ChatColor.GREEN + "Otomatik Spawn");
        autoSpawnMeta.setLore(List.of(
            ChatColor.GRAY + "Otomatik spawn etkin: " + (config.getBoolean("auto-spawn.enabled", false) ? 
                ChatColor.GREEN + "Açık" : ChatColor.RED + "Kapalı"),
            "",
            ChatColor.GRAY + "Tıklayarak aç/kapat"
        ));
        autoSpawnItem.setItemMeta(autoSpawnMeta);
        gui.setItem(11, autoSpawnItem);

        // Spawn zamanı ayarı
        ItemStack spawnTimeItem = new ItemStack(Material.CLOCK);
        ItemMeta spawnTimeMeta = spawnTimeItem.getItemMeta();
        spawnTimeMeta.setDisplayName(ChatColor.GREEN + "Spawn Zamanı Ayarla");
        spawnTimeMeta.setLore(List.of(
            ChatColor.GRAY + "Mevcut spawn zamanı: " + ChatColor.YELLOW + config.getLong("auto-spawn.delay", 86400) + " saniye",
            "",
            ChatColor.GRAY + "Tıklayarak spawn zamanını değiştir"
        ));
        spawnTimeItem.setItemMeta(spawnTimeMeta);
        gui.setItem(15, spawnTimeItem);

        // Geri butonu
        ItemStack backItem = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backItem.getItemMeta();
        backMeta.setDisplayName(ChatColor.GREEN + "Geri");
        backItem.setItemMeta(backMeta);
        gui.setItem(22, backItem);

        player.openInventory(gui);
    }

    private String getMobHeadOwner(EntityType type) {
        switch (type) {
            case SHEEP: return "MHF_Sheep";
            case COW: return "MHF_Cow";
            case PIG: return "MHF_Pig";
            case CHICKEN: return "MHF_Chicken";
            case ZOMBIE: return "MHF_Zombie";
            case SKELETON: return "MHF_Skeleton";
            case CREEPER: return "MHF_Creeper";
            case ENDERMAN: return "MHF_Enderman";
            case SPIDER: return "MHF_Spider";
            case WOLF: return "MHF_Wolf";
            case FROG: return "MHF_Frog";
            case ALLAY: return "MHF_Allay";
            case WARDEN: return "MHF_Warden";
            case AXOLOTL: return "MHF_Axolotl";
            case BEE: return "MHF_Bee";
            case CAT: return "MHF_Ocelot";
            case DOLPHIN: return "MHF_Dolphin";
            case FOX: return "MHF_Fox";
            case GOAT: return "MHF_Goat";
            case PANDA: return "MHF_Panda";
            case PARROT: return "MHF_Parrot";
            case RABBIT: return "MHF_Rabbit";
            case TURTLE: return "MHF_Turtle";
            case HORSE: return "MHF_Horse";
            case LLAMA: return "MHF_Llama";
            default: return "MHF_Question";
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        
        Player player = (Player) event.getWhoClicked();
        Inventory clicked = event.getClickedInventory();
        ItemStack item = event.getCurrentItem();
        
        if (item == null || clicked == null) return;
        
        if (event.getView().getTitle().equals(ChatColor.BLUE + "Pinata Kontrol Paneli")) {
            event.setCancelled(true);
            
            if (item.getType() == Material.PLAYER_HEAD && item.getItemMeta().getDisplayName().equals(ChatColor.GREEN + "Mob Seç")) {
                openMobSelectionGUI(player);
            } 
            else if (item.getType() == Material.NETHER_STAR && item.getItemMeta().getDisplayName().equals(ChatColor.GREEN + "Pinata Türü")) {
                openPinataTypeSelectionGUI(player);
            }
            else if (item.getType() == Material.COMPASS && item.getItemMeta().getDisplayName().equals(ChatColor.GREEN + "Konum Ayarla")) {
                selectedLocation = player.getTargetBlock(null, 50).getLocation().add(0.5, 0, 0.5);
                player.sendMessage(ChatColor.GREEN + "Pinata konumu ayarlandı: " + 
                    selectedLocation.getWorld().getName() + ", " + 
                    selectedLocation.getBlockX() + ", " + 
                    selectedLocation.getBlockY() + ", " + 
                    selectedLocation.getBlockZ());
                openMainGUI(player);
            }
            else if (item.getType() == Material.CLOCK && item.getItemMeta().getDisplayName().equals(ChatColor.GREEN + "Geri Sayım Ayarları")) {
                boolean current = config.getBoolean("countdown.enabled", true);
                config.set("countdown.enabled", !current);
                saveConfig();
                player.sendMessage(ChatColor.GREEN + "Geri sayım " + (!current ? "açıldı" : "kapatıldı"));
                openMainGUI(player);
            }
            else if (item.getType() == Material.LIME_DYE && item.getItemMeta().getDisplayName().equals(ChatColor.GREEN + "Pinata Başlat")) {
                if (selectedLocation == null) {
                    player.sendMessage(ChatColor.RED + "Önce bir konum seçmelisiniz!");
                    return;
                }
                
                startPinataEvent(player, selectedLocation, selectedMobType, selectedPinataType);
                player.closeInventory();
            }
            else if (item.getType() == Material.BOOK && item.getItemMeta().getDisplayName().equals(ChatColor.GREEN + "İstatistikler")) {
                openStatsGUI(player);
            }
            else if (item.getType() == Material.BARRIER && item.getItemMeta().getDisplayName().equals(ChatColor.RED + "Kapat")) {
                player.closeInventory();
            }
        }
        else if (event.getView().getTitle().equals(ChatColor.BLUE + "Mob Seç")) {
            event.setCancelled(true);
            
            if (item.getType() == Material.PLAYER_HEAD) {
                SkullMeta meta = (SkullMeta) item.getItemMeta();
                for (EntityType type : EntityType.values()) {
                    if (meta.getOwner() != null && meta.getOwner().equals(getMobHeadOwner(type))) {
                        selectedMobType = type;
                        player.sendMessage(ChatColor.GREEN + "Pinata mobu ayarlandı: " + type.name());
                        openMainGUI(player);
                        return;
                    }
                }
            }
            else if (item.getType() == Material.ARROW && item.getItemMeta().getDisplayName().equals(ChatColor.GREEN + "Geri")) {
                openMainGUI(player);
            }
        }
        else if (event.getView().getTitle().equals(ChatColor.BLUE + "Pinata Türü Seç")) {
            event.setCancelled(true);
            
            if (item.getType() != Material.AIR && item.getType() != Material.ARROW) {
                for (PinataType type : PinataType.values()) {
                    if (item.getType() == type.getIcon() && item.getItemMeta().getDisplayName().contains(type.getDisplayName())) {
                        selectedPinataType = type;
                        player.sendMessage(ChatColor.GREEN + "Pinata türü ayarlandı: " + type.getDisplayName());
                        openMainGUI(player);
                        return;
                    }
                }
            }
            else if (item.getType() == Material.ARROW && item.getItemMeta().getDisplayName().equals(ChatColor.GREEN + "Geri")) {
                openMainGUI(player);
            }
        }
        else if (event.getView().getTitle().equals(ChatColor.BLUE + "Pinata İstatistikleri")) {
            event.setCancelled(true);
            
            if (item.getType() == Material.ARROW && item.getItemMeta().getDisplayName().equals(ChatColor.GREEN + "Geri")) {
                openMainGUI(player);
            }
        }
        else if (event.getView().getTitle().equals(ChatColor.BLUE + "Zaman Ayarları")) {
            event.setCancelled(true);

            if (item.getType() == Material.CLOCK && item.getItemMeta().getDisplayName().equals(ChatColor.GREEN + "Otomatik Spawn")) {
                boolean current = config.getBoolean("auto-spawn.enabled", false);
                config.set("auto-spawn.enabled", !current);
                saveConfig();
                player.sendMessage(ChatColor.GREEN + "Otomatik spawn " + (!current ? "açıldı" : "kapatıldı"));
                openTimeSettingGUI(player);
            } else if (item.getType() == Material.CLOCK && item.getItemMeta().getDisplayName().equals(ChatColor.GREEN + "Spawn Zamanı Ayarla")) {
                // Zaman ayarlama işlemleri burada yapılabilir
            }
        }
    }

    private void startPinataEvent(Player player, Location location, EntityType type, PinataType pinataType) {
        if (config.getBoolean("countdown.enabled", true)) {
            startCountdown(player, location, type, pinataType);
        } else {
            spawnPinata(location, type, pinataType, true);
            player.sendMessage(ChatColor.GREEN + "Pinata başlatıldı!");
        }
    }

    private void startCountdown(Player player, Location location, EntityType type, PinataType pinataType) {
        int countdownTime = config.getInt("countdown.time", 5);

        BukkitRunnable countdownTask = new BukkitRunnable() {
            int timeLeft = countdownTime;

            @Override
            public void run() {
                if (timeLeft <= 0) {
                    spawnPinata(location, type, pinataType, true);
                    player.sendMessage(ChatColor.GREEN + "Pinata başlatıldı!");
                    cancel();
                    return;
                }

                for (Player online : Bukkit.getOnlinePlayers()) {
                    online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                    online.sendTitle(
                        ChatColor.GOLD + "Pinata Başlıyor!",
                        ChatColor.RED + "Kalan Süre: " + ChatColor.WHITE + timeLeft + " saniye",
                        10, 20, 10
                    );
                }

                timeLeft--;
            }
        };

        countdownTask.runTaskTimer(this, 0, 20);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', 
            "&6Pinata Komutları:\n" +
            "&e/pinata gui &7- Kontrol panelini açar\n" +
            "&e/pinata spawn <mob> [tür] &7- Pinata oluştur\n" +
            "&e/pinata addlocation [isim] &7- Mevcut konumu kaydet\n" +
            "&e/pinata listlocations &7- Kayıtlı konumları listele\n" +
            "&e/pinata start &7- Tüm pinataları başlat\n" +
            "&e/pinata stats [oyuncu] &7- İstatistikleri görüntüle\n" +
            "&e/pinata reload &7- Ayarları yeniden yükle"));
    }

    private void handleSpawnCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Kullanım: /pinata spawn <mob> [tür]");
            return;
        }
        
        try {
            EntityType type = EntityType.valueOf(args[1].toUpperCase());
            PinataType pinataType = args.length > 2 ? 
                PinataType.valueOf(args[2].toUpperCase()) : PinataType.NORMAL;
            
            if (sender instanceof Player) {
                Player player = (Player) sender;
                if (spawnPinata(player.getLocation(), type, pinataType, true)) {
                    player.sendMessage(ChatColor.GREEN + "Pinata oluşturuldu!");
                } else {
                    player.sendMessage(ChatColor.RED + "Pinata oluşturulamadı!");
                }
            }
        } catch (IllegalArgumentException e) {
            sender.sendMessage(ChatColor.RED + "Geçersiz mob tipi veya pinata türü! Geçerli tipler: " + 
                Arrays.stream(EntityType.values())
                    .filter(EntityType::isAlive)
                    .map(Enum::name)
                    .collect(Collectors.joining(", ")) +
                "\nGeçerli pinata türleri: " + 
                Arrays.stream(PinataType.values())
                    .map(Enum::name)
                    .collect(Collectors.joining(", ")));
        }
    }

    private void handleAddLocationCommand(CommandSender sender, String[] args) {
        if (sender instanceof Player) {
            Player player = (Player) sender;
            String locName = args.length > 1 ? args[1] : "konum_" + System.currentTimeMillis();
            
            if (!dataConfig.contains("locations")) {
                dataConfig.createSection("locations");
            }
            
            ConfigurationSection locations = dataConfig.getConfigurationSection("locations");
            locations.set(locName + ".world", player.getLocation().getWorld().getName());
            locations.set(locName + ".x", player.getLocation().getX());
            locations.set(locName + ".y", player.getLocation().getY());
            locations.set(locName + ".z", player.getLocation().getZ());
            locations.set(locName + ".yaw", player.getLocation().getYaw());
            locations.set(locName + ".pitch", player.getLocation().getPitch());
            locations.set(locName + ".type", selectedMobType.name());
            locations.set(locName + ".autoSpawn", true);
            
            try {
                dataConfig.save(dataFile);
                player.sendMessage(ChatColor.GREEN + "'" + locName + "' konumu eklendi!");
            } catch (IOException e) {
                player.sendMessage(ChatColor.RED + "Konum kaydedilirken hata oluştu!");
                getLogger().severe("Konum kaydedilirken hata: " + e.getMessage());
            }
        }
    }

    private void handleListLocationsCommand(CommandSender sender) {
        if (!dataConfig.contains("locations")) {
            sender.sendMessage(ChatColor.RED + "Kayıtlı pinata konumu yok!");
            return;
        }
        
        sender.sendMessage(ChatColor.GOLD + "Pinata Konumları:");
        ConfigurationSection locations = dataConfig.getConfigurationSection("locations");
        for (String key : locations.getKeys(false)) {
            World world = getServer().getWorld(locations.getString(key + ".world"));
            if (world == null) continue;
            
            Location loc = new Location(
                world,
                locations.getDouble(key + ".x"),
                locations.getDouble(key + ".y"),
                locations.getDouble(key + ".z"),
                (float) locations.getDouble(key + ".yaw", 0),
                (float) locations.getDouble(key + ".pitch", 0)
            );
            
            String type = locations.getString(key + ".type", "SHEEP");
            boolean autoSpawn = locations.getBoolean(key + ".autoSpawn", false);
            
            sender.sendMessage(ChatColor.YELLOW + "- " + key + 
                ChatColor.WHITE + ": " + loc.getWorld().getName() + 
                " (" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ")" +
                " | Tip: " + type + 
                " | Otomatik: " + (autoSpawn ? ChatColor.GREEN + "Açık" : ChatColor.RED + "Kapalı"));
        }
    }

    private void handleStartCommand(CommandSender sender) {
        spawnAllPinatas();
        sender.sendMessage(ChatColor.GREEN + "Kayıtlı tüm pinatalar başlatıldı!");
    }

    private void handleStatsCommand(CommandSender sender, String[] args) {
        if (args.length > 1 && sender.hasPermission("pinata.admin.stats")) {
            Player target = getServer().getPlayer(args[1]);
            if (target != null) {
                PlayerStats stats = playerStats.getOrDefault(target.getUniqueId(), 
                    new PlayerStats(target.getUniqueId(), 0, 0, 0, 0));
                sender.sendMessage(ChatColor.GOLD + target.getName() + " İstatistikleri:");
                sender.sendMessage(ChatColor.GRAY + "Toplam Hasar: " + ChatColor.RED + stats.getTotalDamage());
                sender.sendMessage(ChatColor.GRAY + "Yıktığın Pinatalar: " + ChatColor.GOLD + stats.getPinatasDestroyed());
                sender.sendMessage(ChatColor.GRAY + "Kazanılan Ödüller: " + ChatColor.GOLD + stats.getRewardsEarned());
                sender.sendMessage(ChatColor.GRAY + "Kazanılan Para: " + ChatColor.GOLD + stats.getMoneyEarned());
            } else {
                sender.sendMessage(ChatColor.RED + "Oyuncu bulunamadı veya çevrimiçi değil!");
            }
        } else if (sender instanceof Player) {
            openStatsGUI((Player) sender);
        } else {
            sender.sendMessage(ChatColor.RED + "Konsoldan kendi istatistiklerinizi görüntüleyemezsiniz!");
        }
    }

    private void handleReloadCommand(CommandSender sender) {
        reloadConfig();
        config = getConfig();
        sender.sendMessage(ChatColor.GREEN + "Ayarlar yeniden yüklendi!");
    }

    private void spawnHologram(LivingEntity pinata) {
        if (holograms.containsKey(pinata.getUniqueId())) {
            holograms.get(pinata.getUniqueId()).remove();
        }

        ArmorStand hologram = pinata.getWorld().spawn(pinata.getLocation().add(0, 2, 0), ArmorStand.class);
        hologram.setGravity(false);
        hologram.setVisible(false);
        hologram.setCustomNameVisible(true);
        hologram.setMarker(true);

        updateHologram(pinata, hologram);
        holograms.put(pinata.getUniqueId(), hologram);
    }

    private void updateHologram(LivingEntity pinata, ArmorStand hologram) {
        double health = pinata.getHealth();
        double maxHealth = pinata.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
        String color = getColorFromEntity(pinata);
        hologram.setCustomName(ChatColor.translateAlternateColorCodes('&', color + String.format("%.1f/%.1f HP", health, maxHealth)));
    }

    private String getColorFromEntity(LivingEntity entity) {
        if (entity instanceof Sheep) {
            DyeColor color = ((Sheep) entity).getColor();
            java.awt.Color awtColor = new java.awt.Color(getColorFromDye(color));
            return net.md_5.bungee.api.ChatColor.of(awtColor).toString(); // Çakışmayı çözmek için java.awt.Color kullanıldı.
        }
        return net.md_5.bungee.api.ChatColor.WHITE.toString();
    }

    private enum PinataType {
        NORMAL(Material.SHEEP_SPAWN_EGG, ChatColor.GREEN, "Normal"),
        ELITE(Material.BLAZE_SPAWN_EGG, ChatColor.BLUE, "Elit"),
        LEGENDARY(Material.DRAGON_EGG, ChatColor.GOLD, "Efsanevi");
        
        private final Material icon;
        private final ChatColor displayColor;
        private final String displayName;
        
        PinataType(Material icon, ChatColor displayColor, String displayName) {
            this.icon = icon;
            this.displayColor = displayColor;
            this.displayName = displayName;
        }
        
        public Material getIcon() {
            return icon;
        }
        
        public ChatColor getDisplayColor() {
            return displayColor;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }

    private static class PinataData {
        private final Location location;
        private final EntityType entityType;
        private final double health;
        private final boolean respawn;
        private final int respawnDelay;
        private final PinataType pinataType;
        
        public PinataData(Location location, EntityType entityType, double health, 
                         boolean respawn, int respawnDelay, PinataType pinataType) {
            this.location = location;
            this.entityType = entityType;
            this.health = health;
            this.respawn = respawn;
            this.respawnDelay = respawnDelay;
            this.pinataType = pinataType;
        }
        
        public Location getLocation() {
            return location;
        }
        
        public EntityType getEntityType() {
            return entityType;
        }
        
        public double getHealth() {
            return health;
        }
        
        public boolean shouldRespawn() {
            return respawn;
        }
        
        public int getRespawnDelay() {
            return respawnDelay;
        }
        
        public PinataType getPinataType() {
            return pinataType;
        }
    }

    private static class PlayerStats {
        private final UUID playerId;
        private int totalDamage;
        private int pinatasDestroyed;
        private int rewardsEarned;
        private double moneyEarned;
        
        public PlayerStats(UUID playerId, int totalDamage, int pinatasDestroyed, 
                          int rewardsEarned, double moneyEarned) {
            this.playerId = playerId;
            this.totalDamage = totalDamage;
            this.pinatasDestroyed = pinatasDestroyed;
            this.rewardsEarned = rewardsEarned;
            this.moneyEarned = moneyEarned;
        }
        
        public UUID getPlayerId() {
            return playerId;
        }
        
        public int getTotalDamage() {
            return totalDamage;
        }
        
        public void addTotalDamage(double damage) {
            this.totalDamage += damage;
        }
        
        public int getPinatasDestroyed() {
            return pinatasDestroyed;
        }
        
        public void incrementPinatasDestroyed() {
            this.pinatasDestroyed++;
        }
        
        public int getRewardsEarned() {
            return rewardsEarned;
        }
        
        public void incrementRewardsEarned() {
            this.rewardsEarned++;
        }
        
        public void addRewardsEarned(int amount) {
            this.rewardsEarned += amount;
        }
        
        public double getMoneyEarned() {
            return moneyEarned;
        }
        
        public void addMoneyEarned(double amount) {
            this.moneyEarned += amount;
        }
    }
}