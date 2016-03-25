package com.amethystmc.uhc;

import mkremins.fanciful.FancyMessage;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.*;

/**
 * @author Jackson
 * @version 1.0
 */
@SuppressWarnings({"UnusedDeclaration", "ConstantConditions"})
public class Teams extends JavaPlugin implements Listener {

    private static final List<ChatColor> colors = new ArrayList<ChatColor>();
    private static final List<ChatColor> styles = new ArrayList<ChatColor>();
    private static final Random rand = new Random();
    private static Teams instance;
    private static int teamId = 1;
    private static boolean fallDamageDisabled = false;

    static {
        for (ChatColor chatColor : ChatColor.values()) {
            if (chatColor.isColor()) {
                colors.add(chatColor);
            }
            if (chatColor.isFormat()) {
                styles.add(chatColor);
            }
        }
    }

    private int maxTeamSize;
    private List<Team> teams;
    private List<Team> lockedTeams;
    private boolean canConfigure;
    private TreeMap<String, SubCommand> subCommands;
    private HashMap<UUID, Location> offlineScatterLocations;
    private List<TeamInvite> teamInvites;
    private Permission teamsAdmin;
    private Permission lockTeam;
    private Permission scatter;

    public static Teams getInstance() {
        return instance;
    }

    private static String getRandomChatStyle() {
        String style = "" + colors.get(rand.nextInt(colors.size()));
        ChatColor format = styles.get(rand.nextInt(styles.size()));
        if (format != ChatColor.RESET && format != ChatColor.MAGIC) {
            style += format;
        }
        return style;
    }

    public void onEnable() {
        instance = this;
        this.saveDefaultConfig();
        this.maxTeamSize = 1;
        this.canConfigure = true;
        this.teams = new ArrayList<Team>();
        this.lockedTeams = new ArrayList<Team>();
        this.subCommands = new TreeMap<String, SubCommand>();
        this.offlineScatterLocations = new HashMap<UUID, Location>();
        this.teamInvites = new ArrayList<TeamInvite>();
        this.teamsAdmin = new Permission("teams.admin", PermissionDefault.OP);
        this.lockTeam = new Permission("teams.lock", PermissionDefault.OP);
        this.scatter = new Permission("scatter.scatter", PermissionDefault.OP);
        this.getServer().getPluginManager().addPermission(teamsAdmin);
        this.getServer().getPluginManager().addPermission(lockTeam);

        for (Team team : new HashSet<Team>(getScoreboard().getTeams())) { //Full reset!
            team.unregister();
        }

        // Basic User Commands
        this.subCommands.put("create", new CreateTeam());
        this.subCommands.put("leave", new LeaveTeam());
        this.subCommands.put("list", new ListTeamMembers());
        this.subCommands.put("kick", new KickTeamMember());
        this.subCommands.put("invite", new InvitePlayer());
        this.subCommands.put("invites", new ListTeamInvites());
        this.subCommands.put("accept", new AcceptTeamInvite());
        this.subCommands.put("deny", new DenyTeamInvite());
        this.subCommands.put("lock", new LockTeam());


        // Admin commands
        this.subCommands.put("setmax", new SetMaxTeamSize());
        this.subCommands.put("kicksolos", new KickSolos());
        this.subCommands.put("deleteteam", new DeleteTeam());
        this.subCommands.put("fill", new FillTeams());

        this.getCommand("scatterpeoplenow").setExecutor(new ScatterCommand());//:/

        this.getServer().getPluginManager().registerEvents(this, this);
        this.getServer().getScheduler().runTaskTimerAsynchronously(this, new TeamInviteTask(), 0, 0);
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("team")) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                if (!canConfigure) {
                    player.sendMessage(ChatColor.RED + "Teams are currently locked!");
                    return true;
                }
                if (args.length < 1) {
                    showHelp(player);
                } else {
                    if (!subCommands.containsKey(args[0].toLowerCase())) {
                        showHelp(player);
                    } else {
                        SubCommand cmd = subCommands.get(args[0].toLowerCase());
                        if (!cmd.hasPermission(player)) {
                            showHelp(player);
                            return true;
                        }
                        List<String> realArgs = new ArrayList<String>();
                        realArgs.addAll(Arrays.asList(args).subList(1, args.length));

                        cmd.run(player, realArgs.toArray(new String[realArgs.size()]));
                    }
                }
            } else {
                sender.sendMessage(ChatColor.RED + "You must be a player to do this!");
            }
        } else if (command.getName().equalsIgnoreCase("teamlock")) {
            if (sender.hasPermission(teamsAdmin)) {
                if (args.length < 1) {
                    sender.sendMessage(ChatColor.GOLD + "Team lock status: " + (!canConfigure ? ChatColor.RED + "Enabled" : ChatColor.GREEN + "Disabled") + ChatColor.GOLD + "!");
                    return true;
                }
                if (args[0].equalsIgnoreCase("on")) {
                    canConfigure = false;
                    sender.sendMessage(ChatColor.GOLD + "Team lock status: " + (!canConfigure ? ChatColor.RED + "Enabled" : ChatColor.GREEN + "Disabled") + ChatColor.GOLD + "!");
                } else {
                    canConfigure = true;
                    sender.sendMessage(ChatColor.GOLD + "Team lock status: " + (!canConfigure ? ChatColor.RED + "Enabled" : ChatColor.GREEN + "Disabled") + ChatColor.GOLD + "!");
                }
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerFallDamage(EntityDamageEvent event) {
        if (event.getEntity().getType() == EntityType.PLAYER && event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            if (fallDamageDisabled) {
                event.setCancelled(true);
                // Resets fall distance, otherwise they'll take damage if they don't jump before fallDamageDistabled is false
                event.getEntity().setFallDistance(0F);
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(final PlayerJoinEvent event) {
        if (offlineScatterLocations.containsKey(event.getPlayer().getUniqueId())) {
            event.getPlayer().sendMessage(ChatColor.GOLD + "Teleporting to scatter location!");
            Bukkit.broadcast(ChatColor.RED + "Scatter Admin Alert: " + ChatColor.GOLD + event.getPlayer().getName() + " rejoined, teleporting to scatter location!", scatter.getName());
            Bukkit.getScheduler().runTaskLater(this, new Runnable() {
                public void run() {
                    if (event.getPlayer().isOnline()) {
                        if (offlineScatterLocations.containsKey(event.getPlayer().getUniqueId())) {
                            event.getPlayer().teleport(offlineScatterLocations.get(event.getPlayer().getUniqueId()).getWorld().getHighestBlockAt(offlineScatterLocations.get(event.getPlayer().getUniqueId())).getLocation().clone().add(0, 2, 0));
                            offlineScatterLocations.remove(event.getPlayer().getUniqueId());
                        }
                    }
                }
            }, 20);
        }
    }

    public int randInt(int min, int max) {
        // nextInt is normally exclusive of the top value,
        // so add 1 to make it inclusive
        return rand.nextInt((max - min) + 1) + min;
    }

    private void showHelp(Player p) {
        p.sendMessage(ChatColor.GOLD + "=========== " + ChatColor.GOLD + "" + ChatColor.BOLD + "Team Commands" + ChatColor.GOLD + " ===========");
        for (Map.Entry<String, SubCommand> commandEntry : subCommands.entrySet()) {
            if (commandEntry.getValue().hasPermission(p))
                p.sendMessage(ChatColor.GOLD + " - " + ChatColor.YELLOW + "/team " + commandEntry.getKey() + ChatColor.GOLD + " " + commandEntry.getValue().getDescription());
        }
    }

    private Scoreboard getScoreboard() {
        return Bukkit.getScoreboardManager().getMainScoreboard();
    }

    private Team getTeamForPlayer(OfflinePlayer p) {
        for (Team team : this.teams) {
            if (team.hasPlayer(p)) return team;
        }
        return null;
    }

    private boolean isOnTeam(OfflinePlayer p) {
        return getTeamForPlayer(p) != null;
    }

    private void msgTeam(Team team, String msg) {
        for (OfflinePlayer player : team.getPlayers()) {
            if (player.isOnline()) player.getPlayer().sendMessage(msg);
        }
    }

    private void removeTeam(Team team) {
        this.lockedTeams.remove(team);
        this.teams.remove(team);
        clearInvitesForTeam(team);
        team.unregister();
    }

    private List<TeamInvite> getInvitesForPlayer(Player p) {
        List<TeamInvite> invites = new ArrayList<TeamInvite>();
        for (TeamInvite invite : teamInvites) {
            if (invite.getPlayer().equals(p)) invites.add(invite);
        }
        return invites;
    }

    private List<TeamInvite> getInvitesForTeam(Team team) {
        List<TeamInvite> invites = new ArrayList<TeamInvite>();
        for (TeamInvite invite : teamInvites) {
            if (invite.getTeam().equals(team)) invites.add(invite);
        }
        return invites;
    }

    private void clearInvitesForPlayer(Player p) {
        for (TeamInvite invite : getInvitesForPlayer(p)) {
            teamInvites.remove(invite);
        }
    }

    private void clearInvitesForTeam(Team team) {
        for (TeamInvite invite : getInvitesForTeam(team)) {
            teamInvites.remove(invite);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        clearInvitesForPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (isOnTeam(event.getPlayer()))
            event.setFormat(getTeamForPlayer(event.getPlayer()).getPrefix() + "[" + getTeamForPlayer(event.getPlayer()).getDisplayName() + "]" + ChatColor.RESET + " " + event.getFormat());
    }

    private Team getNewTeam() {
        int id = teamId++;
        Team team = getScoreboard().registerNewTeam("UHC" + id);
        teams.add(team);
        team.setCanSeeFriendlyInvisibles(true);
        team.setAllowFriendlyFire(this.getConfig().getBoolean("friendly-fire", false));
        team.setDisplayName("Team " + id);
        team.setPrefix(getRandomChatStyle());
        return team;
    }

    private enum InviteRemoveReason {
        TIMEOUT, CONFIG_LOCKED, MAX_PLAYERS
    }

    private interface SubCommand {

        boolean hasPermission(Player player);

        String getDescription();

        void run(Player player, String[] args);

    }

    private class ScatterCommand implements CommandExecutor {
        public boolean onCommand(CommandSender sender, Command command, String s, String[] args) {
            //System.out.println(args.length + " - " + args);
            //Bukkit.broadcastMessage("FUCK U BUKKIT!");
            if (sender.hasPermission(scatter)) {
                //sender.sendMessage(args.length+"");
                if (args.length < 3) {
                    sender.sendMessage("Not enough args!");
                    return true;
                }
                if (canConfigure) {
                    sender.sendMessage(ChatColor.RED + "Cannot scatter if teams editing is enabled!");
                    return true;
                }
                World world = Bukkit.getWorld(args[0]);
                if (world == null) {
                    sender.sendMessage(ChatColor.RED + "Invalid world!");
                    return true;
                }

                int size;
                int mindist;

                try {
                    size = Integer.valueOf(args[1]);
                    mindist = Integer.valueOf(args[2]);
                } catch (NumberFormatException ex) {
                    return false;
                }

                HashMap<UUID, Location> scatterLocations = new HashMap<UUID, Location>();
                Location lastLocation = null;
                List<UUID> solos = new ArrayList<UUID>();
                long chunkLoadTicks = 0;
                for (OfflinePlayer player : Bukkit.getWhitelistedPlayers()) {
                    Team team = getTeamForPlayer(player);
                    if (team == null) solos.add(player.getUniqueId());
                    if (scatterLocations.containsKey(player.getUniqueId())) continue;

                    Location spawnLoc = null;
                    for (int i = 0; i < 1000; i++) {
                        Location testLoc = new Location(world, randInt(-size, size), 255, randInt(-size, size));
                        if (lastLocation != null) {
                            if (lastLocation.distance(testLoc) < mindist) continue;
                        }
                        while (testLoc.getBlockY() >= 4) {
                            if (testLoc.getBlock().getType() != Material.AIR) break;
                            testLoc.add(0, -1, 0);
                        }
                        if (testLoc.getBlock().getType() == Material.AIR || testLoc.getBlock().getType() == Material.CACTUS || testLoc.getBlock().getType() == Material.WATER || testLoc.getBlock().getType() == Material.STATIONARY_WATER || testLoc.getBlock().getType() == Material.LAVA || testLoc.getBlock().getType() == Material.STATIONARY_LAVA) {
                            continue;
                        }
                        spawnLoc = testLoc;
                    }
                    if (spawnLoc == null) {
                        Bukkit.broadcast(ChatColor.RED + "Scatter Admin Alert: " + ChatColor.GOLD + "Scatter Failed! D:", scatter.getName());
                        return true;
                    }
                    lastLocation = spawnLoc;
                    if (team != null) {
                        for (OfflinePlayer teamPlayer : team.getPlayers()) {
                            scatterLocations.put(teamPlayer.getUniqueId(), spawnLoc);
                        }
                    } else {
                        scatterLocations.put(player.getUniqueId(), spawnLoc);
                    }
                }
//                for(Location loc: scatterLocations.values()){
//                    Chunk chunk = loc.getChunk();
//                    for(int x=-1; x<=1; x++){
//                        for(int z=-1; z<=1; z++) {
//                            Chunk loadingChunk = world.getChunkAt(chunk.getX()+x, chunk.getZ()+z);
//                            if(!loadingChunk.isLoaded()){
//                                loadingChunk.load(true);
//                                loadingChunk.getChunkSnapshot();
//                            }
//                        }
//                    }
//                }
                fallDamageDisabled = true;
                for (Map.Entry<UUID, Location> entry : scatterLocations.entrySet()) {
                    OfflinePlayer player = Bukkit.getOfflinePlayer(entry.getKey());
                    if (player.isOnline()) {
                        player.getPlayer().teleport(entry.getValue().getWorld().getHighestBlockAt(entry.getValue()).getLocation().clone().add(0, 4, 0));
                    } else {
                        offlineScatterLocations.put(entry.getKey(), entry.getValue());
                        Bukkit.broadcast(ChatColor.RED + "Scatter Admin Alert: " + ChatColor.GOLD + player.getName() + " is offline so they have been assigned a spawn location for rejoin later!", scatter.getName());
                    }
                }
                Bukkit.getScheduler().runTaskLater(Teams.getInstance(), new Runnable() {
                    public void run() {
                        fallDamageDisabled = false;
                    }
                }, 40);
            }
            return true;
        }
    }

    private class CreateTeam implements SubCommand {

        FancyMessage createMessage = new FancyMessage("You created a new team! Now start ").color(ChatColor.GOLD).then("inviting people!").color(ChatColor.GOLD).suggest("/team invite <player>");

        public boolean hasPermission(Player player) {
            return !isOnTeam(player);
        }

        public String getDescription() {
            return "Creates a team";
        }

        public void run(Player player, String[] args) {
            Team team = getNewTeam();
            team.addPlayer(player);
            createMessage.send(player);
        }
    }

    private class LeaveTeam implements SubCommand {


        public boolean hasPermission(Player player) {
            return isOnTeam(player);
        }


        public String getDescription() {
            return "Leaves your current team";
        }


        public void run(Player player, String[] args) {
            Team team = getTeamForPlayer(player);
            team.removePlayer(player);
            player.sendMessage(ChatColor.RED + "You left the team!");
            msgTeam(team, ChatColor.GOLD + player.getName() + " left your team!");
            if (team.getPlayers().size() < 1) {
                removeTeam(team);
            }
        }
    }

    private class ListTeamMembers implements SubCommand {


        public boolean hasPermission(Player player) {
            return isOnTeam(player);
        }


        public String getDescription() {
            return "Lists the current members in your team";
        }


        public void run(Player player, String[] args) {
            Team team = getTeamForPlayer(player);
            player.sendMessage(ChatColor.GOLD + "========= " + ChatColor.GOLD + "" + ChatColor.BOLD + "Team Members" + ChatColor.GOLD + " =========");
            for (OfflinePlayer offlinePlayer : team.getPlayers()) {
                FancyMessage msg = new FancyMessage(" - " + offlinePlayer.getName() + " ").color(ChatColor.GOLD);
                if (!offlinePlayer.getUniqueId().equals(player.getUniqueId())) {
                    msg.then("[X]").color(ChatColor.RED).style(ChatColor.BOLD).command("/team kick " + offlinePlayer.getName());
                }
                msg.send(player);
            }
        }
    }

    private class KickTeamMember implements SubCommand {

        public boolean hasPermission(Player player) {
            return isOnTeam(player) && getTeamForPlayer(player).getPlayers().size() > 1;
        }

        public String getDescription() {
            return "Kicks specified player from your team";
        }

        public void run(Player player, String[] args) {
            if (args.length < 1) {
                subCommands.get("list").run(player, args);
                return;
            }
            Team team = getTeamForPlayer(player);
            OfflinePlayer found = null;
            for (OfflinePlayer offlinePlayer : team.getPlayers()) {
                if (offlinePlayer.getName().equals(args[0])) {
                    found = offlinePlayer;
                    break;
                }
            }
            if (found == null) {
                subCommands.get("list").run(player, args);
                return;
            }
            if (found.getUniqueId().equals(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "Cannot kick yourself from the team.");
                return;
            }
            team.removePlayer(found);
            if (found.isOnline()) {
                found.getPlayer().sendMessage(ChatColor.GOLD + "You were kicked from the team by " + player.getName() + "!");
            }
            msgTeam(team, ChatColor.GOLD + found.getName() + " was kicked from your team!");
        }
    }

    @SuppressWarnings("deprecation")
    private class InvitePlayer implements SubCommand {

        public boolean hasPermission(Player player) {
            return isOnTeam(player) && getTeamForPlayer(player).getPlayers().size() < maxTeamSize;
        }

        public String getDescription() {
            return "Invites a player to your team";
        }

        public void run(Player player, String[] args) {
            if (args.length < 1) {
                player.sendMessage(ChatColor.RED + "Please specify a player to invite");
                return;
            }
            Team team = getTeamForPlayer(player);
            Player invitee = Bukkit.getPlayer(args[0]);
            if (invitee == null) {
                player.sendMessage(ChatColor.RED + "No player online by the name \"" + args[0] + "\"");
                return;
            }
            if (hasInviteForTeam(team, invitee)) {
                player.sendMessage(ChatColor.RED + "This team has already invited that player!");
                return;
            }
            if (isOnTeam(invitee)) {
                player.sendMessage(ChatColor.RED + "That player is already on a team!");
                return;
            }

            teamInvites.add(new TeamInvite(team, invitee));
            msgTeam(team, ChatColor.GOLD + player.getName() + " invited " + invitee.getName() + " to the team!");
            FancyMessage msg = new FancyMessage(player.getName() + " invited you to their team (").color(ChatColor.GOLD);
            boolean firstAdded = false;
            for (OfflinePlayer offlinePlayer : team.getPlayers()) {
                String playerText = (firstAdded ? ", " : "");
                playerText += offlinePlayer.getName();
                msg.then(playerText).color(ChatColor.GOLD);
                firstAdded = true;
            }
            msg.then(") ").color(ChatColor.GOLD).then("[ACCEPT]").color(ChatColor.GREEN).style(ChatColor.BOLD).command("/team accept " + team.getName());
            msg.then(" ");
            msg.then("[DENY]").color(ChatColor.RED).style(ChatColor.BOLD).command("/team deny " + team.getName());
            msg.send(invitee);
        }

        private boolean hasInviteForTeam(Team team, Player player) {
            for (TeamInvite invite : getInvitesForPlayer(player)) {
                if (invite.getTeam().equals(team)) return true;
            }
            return false;
        }
    }

    private class ListTeamInvites implements SubCommand {

        public boolean hasPermission(Player player) {
            return !isOnTeam(player) && getInvitesForPlayer(player).size() > 0;
        }

        public String getDescription() {
            return "List Current Invites";
        }

        public void run(Player player, String[] args) {
            player.sendMessage(ChatColor.GOLD + "========= " + ChatColor.GOLD + "" + ChatColor.BOLD + "Team Invites" + ChatColor.GOLD + " =========");
            for (TeamInvite invite : getInvitesForPlayer(player)) {
                FancyMessage msg = new FancyMessage(" - ").color(ChatColor.GOLD);
                boolean firstAdded = false;
                for (OfflinePlayer offlinePlayer : invite.getTeam().getPlayers()) {
                    String playerText = (firstAdded ? ", " : "");
                    playerText += offlinePlayer.getName();
                    msg.then(playerText).color(ChatColor.GOLD);
                    firstAdded = true;
                }
                msg.then(" ");
                msg.then("[ACCEPT]").color(ChatColor.GREEN).style(ChatColor.BOLD).command("/team accept " + invite.getTeam().getName());
                msg.then(" ");
                msg.then("[DENY]").color(ChatColor.RED).style(ChatColor.BOLD).command("/team deny " + invite.getTeam().getName());
                msg.send(player);
            }
        }
    }

    private class AcceptTeamInvite implements SubCommand {

        public boolean hasPermission(Player player) {
            return !isOnTeam(player) && getInvitesForPlayer(player).size() > 0;
        }

        public String getDescription() {
            return "Accepts the invite from specified team";
        }

        public void run(Player player, String[] args) {
            if (args.length < 1) {
                subCommands.get("invites").run(player, args);
                return;
            }
            TeamInvite found = null;
            for (TeamInvite invite : getInvitesForPlayer(player)) {
                if (invite.getTeam().getName().equals(args[0])) {
                    found = invite;
                    break;
                }
            }
            if (found == null) {
                subCommands.get("invites").run(player, args);
                return;
            }
            found.getTeam().addPlayer(player);
            msgTeam(found.getTeam(), ChatColor.GOLD + player.getName() + " joined the team!");
            clearInvitesForPlayer(player);
        }
    }

    private class DenyTeamInvite implements SubCommand {

        public boolean hasPermission(Player player) {
            return !isOnTeam(player) && getInvitesForPlayer(player).size() > 0;
        }

        public String getDescription() {
            return "Denys the invite from the specified team";
        }

        public void run(Player player, String[] args) {
            if (args.length < 1) {
                subCommands.get("invites").run(player, args);
                return;
            }
            TeamInvite found = null;
            for (TeamInvite invite : getInvitesForPlayer(player)) {
                if (invite.getTeam().getName().equals(args[0])) {
                    found = invite;
                    break;
                }
            }
            if (found == null) {
                subCommands.get("invites").run(player, args);
                return;
            }
            teamInvites.remove(found);
            msgTeam(found.getTeam(), ChatColor.GOLD + player.getName() + " denied the team invite!");
        }
    }

    private class LockTeam implements SubCommand {

        public boolean hasPermission(Player player) {
            return isOnTeam(player) && player.hasPermission(lockTeam);
        }

        public String getDescription() {
            return "Locks your team gaining new players when filling slots";
        }

        public void run(Player player, String[] args) {
            Team team = getTeamForPlayer(player);
            if (lockedTeams.contains(team)) {
                lockedTeams.remove(team);
                msgTeam(team, ChatColor.GOLD + "Team unlocked!");
            } else {
                lockedTeams.add(team);
                msgTeam(team, ChatColor.GOLD + "Team locked!");
            }
        }
    }

    private class SetMaxTeamSize implements SubCommand {

        public boolean hasPermission(Player player) {
            return player.hasPermission(teamsAdmin);
        }

        public String getDescription() {
            return "Set the maximum team size (Currently set to " + maxTeamSize + ")";
        }

        public void run(Player player, String[] args) {
            if (args.length < 1) {
                player.sendMessage(ChatColor.RED + "Please specify a new max team size");
            }
            Integer newMaxSize;
            try {
                newMaxSize = Integer.valueOf(args[0]);
                if (newMaxSize <= 1) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException ex) {
                player.sendMessage(ChatColor.RED + "\"" + args[0] + "\" is not a valid number!");
                return;
            }
            maxTeamSize = newMaxSize;
            Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Team max size has been set to " + maxTeamSize + "! Removing all teams that exceed this limit!");
            List<Team> toRemove = new ArrayList<Team>();
            for (Team team : teams) {
                if (team.getPlayers().size() > maxTeamSize) {
                    toRemove.add(team);
                }
            }
            for (Team team : toRemove) {
                msgTeam(team, ChatColor.RED + "Your team got removed due to the max team size change! Please remake your team!");
                removeTeam(team);
            }
        }

    }

    private class KickSolos implements SubCommand {

        public boolean hasPermission(Player player) {
            return player.hasPermission(teamsAdmin);
        }

        public String getDescription() {
            return "Kicks all players who don't have a team or are in an unlocked 1 player team";
        }

        public void run(Player player, String[] args) {
            if (maxTeamSize == 1) {
                player.sendMessage("Please set the max team size higher then 1!");
                return;
            }
            int kicked = 0;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission(teamsAdmin)) continue; //Admins/Host bypass this
                Team team = getTeamForPlayer(p);
                if (team == null || (team.getPlayers().size() < 2 && !lockedTeams.contains(team))) {
                    if (team != null) {
                        removeTeam(team);
                    }
                    p.kickPlayer(getConfig().getString("solo-kick-message"));
                    kicked++;
                }
            }
            player.sendMessage(ChatColor.GOLD + "Kicked " + kicked + " players!");
        }
    }

    private class FillTeams implements SubCommand {

        public boolean hasPermission(Player player) {
            return player.hasPermission(teamsAdmin);
        }

        public String getDescription() {
            return "Fills teams with players who aren't in teams";
        }

        public void run(Player player, String[] args) {
            Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Filling unlocked teams with unteamed players!");
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission(teamsAdmin)) continue;
                if (!isOnTeam(p)) {
                    Team found = null;
                    for (Team team : teams) {
                        if (!lockedTeams.contains(team) && team.getPlayers().size() < maxTeamSize) {
                            found = team;
                            break;
                        }
                    }
                    if (found == null) found = getNewTeam();
                    msgTeam(found, ChatColor.GOLD + p.getName() + " was added to your team in the fill!");
                    String teamPlayers = "";
                    boolean firstAdded = false;
                    for (OfflinePlayer offlinePlayer : found.getPlayers()) {
                        teamPlayers += (firstAdded ? ", " : "") + offlinePlayer.getName();
                        firstAdded = true;
                    }
                    found.addPlayer(p);
                    p.sendMessage(ChatColor.GOLD + "You were placed into a team with " + teamPlayers + "!");
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private class DeleteTeam implements SubCommand {

        public boolean hasPermission(Player player) {
            return player.hasPermission(teamsAdmin);
        }

        public String getDescription() {
            return "Deletes the team associated with the specified player";
        }

        public void run(Player player, String[] args) {
            if (args.length < 1) {
                player.sendMessage(ChatColor.RED + "Please specify a player");
                return;
            }
            Player other = Bukkit.getPlayer(args[0]);
            if (other == null) {
                player.sendMessage(ChatColor.RED + "No player online by the name \"" + args[0] + "\"");
                return;
            }

            Team team = getTeamForPlayer(other);
            msgTeam(team, ChatColor.RED + "Your team got removed by a game administrator!");
            removeTeam(team);
        }
    }

    private class TeamInvite {
        private Team team;
        private Player player;
        private int timeLeft = 600;

        public TeamInvite(Team team, Player player) {
            this.team = team;
            this.player = player;
        }

        public Team getTeam() {
            return team;
        }

        public Player getPlayer() {
            return player;
        }

        public int getTimeLeft() {
            return timeLeft;
        }

        public void setTimeLeft(int timeLeft) {
            this.timeLeft = timeLeft;
        }
    }

    private class TeamInviteTask implements Runnable {

        public void run() {
            Map<TeamInvite, InviteRemoveReason> toRemove = new HashMap<TeamInvite, InviteRemoveReason>();
            for (TeamInvite invite : teamInvites) {
                invite.setTimeLeft(invite.getTimeLeft() - 1);
                if (!canConfigure) {
                    toRemove.put(invite, InviteRemoveReason.CONFIG_LOCKED);
                } else if (invite.getTimeLeft() <= 0) {
                    toRemove.put(invite, InviteRemoveReason.TIMEOUT);
                } else if (invite.getTeam().getPlayers().size() >= maxTeamSize) {
                    toRemove.put(invite, InviteRemoveReason.MAX_PLAYERS);
                }
            }
            for (Map.Entry<TeamInvite, InviteRemoveReason> invite : toRemove.entrySet()) {
                teamInvites.remove(invite.getKey());
                switch (invite.getValue()) {
                    case TIMEOUT:
                        msgTeam(invite.getKey().getTeam(), ChatColor.GOLD + invite.getKey().getPlayer().getName() + "'s invite expired!");
                        break;
                    case MAX_PLAYERS:
                        msgTeam(invite.getKey().getTeam(), ChatColor.GOLD + invite.getKey().getPlayer().getName() + "'s invite got removed because you have hit the maximum number of team members");
                        break;
                }
            }
        }
    }
}
