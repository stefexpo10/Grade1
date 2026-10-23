package ro.grade;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class GradePlugin extends JavaPlugin implements Listener, TabExecutor {

    record Rank(String id, String prefix, int weight, List<String> inherits, List<String> permissions) {}

    private final Map<String, Rank> ranks = new LinkedHashMap<>();
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();
    private File dataFile;
    private YamlConfiguration data;
    private String defaultRank;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        loadRanks();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("grade")).setExecutor(this);
        Bukkit.getOnlinePlayers().forEach(this::apply);
    }

    @Override
    public void onDisable() {
        new ArrayList<>(attachments.keySet()).forEach(this::detach);
    }

    // ---------- Incarcare config ----------

    private void loadRanks() {
        ranks.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("ranks");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection r = sec.getConfigurationSection(id);
                if (r == null) continue;
                ranks.put(id.toLowerCase(), new Rank(
                        id.toLowerCase(),
                        r.getString("prefix", ""),
                        r.getInt("weight", 0),
                        r.getStringList("inherits"),
                        r.getStringList("permissions")));
            }
        }
        defaultRank = getConfig().getString("default-rank", "membru").toLowerCase();
        if (!ranks.containsKey(defaultRank)) {
            getLogger().warning("default-rank '" + defaultRank + "' nu exista in config!");
        }
    }

    // ---------- Date jucatori ----------

    private Rank rankOf(UUID id) {
        String name = data.getString("players." + id, defaultRank);
        Rank r = ranks.get(name.toLowerCase());
        return r != null ? r : ranks.get(defaultRank);
    }

    private void setRank(UUID id, String rankId) {
        data.set("players." + id, rankId);
        saveData();
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Nu pot salva data.yml: " + e.getMessage());
        }
    }

    // ---------- Aplicare grad ----------

    private void collect(Rank rank, Set<String> visited, Map<String, Boolean> out) {
        if (rank == null || !visited.add(rank.id())) return;
        for (String parent : rank.inherits()) collect(ranks.get(parent.toLowerCase()), visited, out);
        for (String p : rank.permissions()) {
            if (p.startsWith("-")) out.put(p.substring(1), false);
            else out.put(p, true);
        }
    }

    private void detach(UUID id) {
        PermissionAttachment old = attachments.remove(id);
        Player p = Bukkit.getPlayer(id);
        if (old != null && p != null) p.removeAttachment(old);
    }

    private void apply(Player p) {
        detach(p.getUniqueId());
        Rank rank = rankOf(p.getUniqueId());
        if (rank == null) return;

        PermissionAttachment att = p.addAttachment(this);
        Map<String, Boolean> perms = new LinkedHashMap<>();
        collect(rank, new HashSet<>(), perms);
        perms.forEach(att::setPermission);
        attachments.put(p.getUniqueId(), att);

        p.playerListName(legacy.deserialize(rank.prefix()).append(Component.text(p.getName())));
        p.recalculatePermissions();
        p.updateCommands();
    }

    // ---------- Evenimente ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        apply(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        attachments.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Rank rank = rankOf(e.getPlayer().getUniqueId());
        String prefix = rank != null ? rank.prefix() : "";
        String format = getConfig().getString("chat-format", "{prefix}{player}&7: &f{message}")
                .replace("{prefix}", prefix);

        e.renderer((source, displayName, message, viewer) -> {
            String f = format.replace("{player}", source.getName());
            int i = f.indexOf("{message}");
            if (i < 0) return legacy.deserialize(f).append(message);
            return legacy.deserialize(f.substring(0, i))
                    .append(message)
                    .append(legacy.deserialize(f.substring(i + "{message}".length())));
        });
    }

    // ---------- Comenzi ----------

    private void msg(CommandSender s, String text) {
        s.sendMessage(legacy.deserialize(text));
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("grade.admin")) {
            msg(s, "&cNu ai permisiune.");
            return true;
        }
        if (a.length == 0) {
            msg(s, "&e/grade <list|set|remove|info|reload>");
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "reload" -> {
                reloadConfig();
                loadRanks();
                Bukkit.getOnlinePlayers().forEach(this::apply);
                msg(s, "&aConfig reincarcat (" + ranks.size() + " grade).");
            }
            case "list" -> {
                msg(s, "&eGrade:");
                ranks.values().stream()
                        .sorted(Comparator.comparingInt(Rank::weight).reversed())
                        .forEach(r -> msg(s, " &7- " + r.prefix() + "&7(" + r.id() + ", prioritate " + r.weight() + ")"));
            }
            case "set" -> {
                if (a.length < 3) { msg(s, "&cFolosire: /grade set <jucator> <grad>"); return true; }
                Rank r = ranks.get(a[2].toLowerCase());
                if (r == null) { msg(s, "&cGradul nu exista."); return true; }
                OfflinePlayer t = Bukkit.getOfflinePlayer(a[1]);
                setRank(t.getUniqueId(), r.id());
                Player online = t.getPlayer();
                if (online != null) apply(online);
                msg(s, "&a" + a[1] + " are acum gradul " + r.prefix().trim());
            }
            case "remove" -> {
                if (a.length < 2) { msg(s, "&cFolosire: /grade remove <jucator>"); return true; }
                OfflinePlayer t = Bukkit.getOfflinePlayer(a[1]);
                data.set("players." + t.getUniqueId(), null);
                saveData();
                Player online = t.getPlayer();
                if (online != null) apply(online);
                msg(s, "&a" + a[1] + " a revenit la gradul implicit.");
            }
            case "info" -> {
                OfflinePlayer t = a.length > 1 ? Bukkit.getOfflinePlayer(a[1])
                        : (s instanceof Player p ? p : null);
                if (t == null) { msg(s, "&cFolosire: /grade info <jucator>"); return true; }
                Rank r = rankOf(t.getUniqueId());
                msg(s, "&e" + (t.getName() != null ? t.getName() : a[1]) + ": " + (r != null ? r.prefix() + "&7(" + r.id() + ")" : "&7niciun grad"));
            }
            default -> msg(s, "&cSubcomanda necunoscuta.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("grade.admin")) return List.of();
        if (a.length == 1) return filter(List.of("list", "set", "remove", "info", "reload"), a[0]);
        if (a.length == 2 && !a[0].equalsIgnoreCase("list") && !a[0].equalsIgnoreCase("reload"))
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), a[1]);
        if (a.length == 3 && a[0].equalsIgnoreCase("set"))
            return filter(new ArrayList<>(ranks.keySet()), a[2]);
        return List.of();
    }

    private List<String> filter(List<String> opts, String prefix) {
        return opts.stream().filter(o -> o.toLowerCase().startsWith(prefix.toLowerCase())).collect(Collectors.toList());
    }
}
