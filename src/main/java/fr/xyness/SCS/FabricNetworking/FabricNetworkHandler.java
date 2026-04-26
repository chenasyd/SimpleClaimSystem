package fr.xyness.SCS.FabricNetworking;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import fr.xyness.SCS.SimpleClaimSystem;
import fr.xyness.SCS.Types.Claim;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class FabricNetworkHandler implements PluginMessageListener {

    public static final String CHANNEL = "simpleclaimsystem:main";
    
    private final SimpleClaimSystem plugin;
    private final Map<UUID, Boolean> fabricPlayers;

    public FabricNetworkHandler(SimpleClaimSystem plugin) {
        this.plugin = plugin;
        this.fabricPlayers = new ConcurrentHashMap<>();
        
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        
        plugin.info("§a[FabricNetwork] §fNetwork channel registered: " + CHANNEL);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals(CHANNEL)) return;

        try {
            String data = new String(message, StandardCharsets.UTF_8);
            String[] parts = data.split("\\|", 4);

            if (parts.length < 2) return;

            String action = parts[0];
            UUID playerId = player.getUniqueId();

            switch (action.toLowerCase()) {
                case "handshake":
                    handleHandshake(player, playerId);
                    break;
                case "request_claims":
                    handleRequestClaims(player, playerId);
                    break;
                case "claim_request":
                    handleClaimRequest(player, playerId, parts);
                    break;
                case "update_claim":
                    handleUpdateClaim(player, playerId, parts);
                    break;
                case "member_action":
                    handleMemberAction(player, playerId, parts);
                    break;
                case "settings_update":
                    handleSettingsUpdate(player, playerId, parts);
                    break;
                case "location_sync":
                    handleLocationSync(player, playerId, parts);
                    break;
            }
        } catch (Exception e) {
            plugin.info("§c[FabricNetwork] §fError processing message: " + e.getMessage());
        }
    }

    private void handleHandshake(Player player, UUID playerId) {
        fabricPlayers.put(playerId, true);
        sendHandshakeResponse(player, true);
        plugin.info("§a[FabricNetwork] §fFabric client connected: " + player.getName());
        
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            syncClaimsToPlayer(player);
        }, 20L);
    }

    private void handleRequestClaims(Player player, UUID playerId) {
        syncClaimsToPlayer(player);
    }

    private void handleClaimRequest(Player player, UUID playerId, String[] parts) {
        if (parts.length < 5) return;

        String claimAction = parts[1];
        String worldName = parts[2];
        int chunkX = Integer.parseInt(parts[3]);
        int chunkZ = Integer.parseInt(parts[4]);

        boolean success = false;
        String message = "";

        switch (claimAction.toLowerCase()) {
            case "create":
                success = createClaimForPlayer(player, worldName, chunkX, chunkZ);
                message = success ? 
                    "领地创建成功！" : 
                    "领地创建失败：请检查权限或经济余额";
                break;
            case "delete":
                success = deleteClaimForPlayer(player, parts.length > 5 ? parts[5] : null);
                message = success ?
                    "领地删除成功！" :
                    "领地删除失败：您不是该领地的所有者";
                break;
            case "expand":
                success = expandClaimForPlayer(player, worldName, chunkX, chunkZ);
                message = success ?
                    "领地扩展成功！" :
                    "领地扩展失败：无法扩展到此位置";
                break;
        }

        sendClaimResult(player, success, message);
    }

    private void handleUpdateClaim(Player player, UUID playerId, String[] parts) {
        if (parts.length < 5) return;

        UUID claimId = UUID.fromString(parts[1]);
        String name = parts[2];
        String description = parts[3];
        boolean forSale = Boolean.parseBoolean(parts[4]);
        double price = parts.length > 5 ? Double.parseDouble(parts[5]) : 0.0;

        Claim claim = plugin.getMain().getClaim(claimId);
        if (claim != null && claim.getOwner().equals(player.getName())) {
            claim.setName(name);
            claim.setDescription(description);
            claim.setSale(forSale);
            claim.setPrice((long)(price * 100));
            
            plugin.getMain().saveClaim(claim);
            
            broadcastClaimUpdate(claimId, name, description, forSale, price);
        } else {
            sendErrorResult(player, "无权修改此领地");
        }
    }

    private void handleMemberAction(Player player, UUID playerId, String[] parts) {
        if (parts.length < 4) return;

        UUID claimId = UUID.fromString(parts[1]);
        UUID targetUuid = UUID.fromString(parts[2]);
        String action = parts[3];

        Claim claim = plugin.getMain().getClaim(claimId);
        if (claim == null || !claim.getOwner().equals(player.getName())) {
            sendErrorResult(player, "无权管理此领地的成员");
            return;
        }

        switch (action.toLowerCase()) {
            case "add":
                OfflinePlayer target = Bukkit.getOfflinePlayer(targetUuid);
                if (target.hasPlayedBefore()) {
                    claim.addMember(targetUuid);
                    plugin.getMain().saveClaim(claim);
                    broadcastMemberAction(claimId, targetUuid, "add");
                }
                break;
            case "remove":
                claim.removeMember(targetUuid);
                plugin.getMain().saveClaim(claim);
                broadcastMemberAction(claimId, targetUuid, "remove");
                break;
            case "ban":
                claim.addBan(targetUuid);
                claim.removeMember(targetUuid);
                plugin.getMain().saveClaim(claim);
                broadcastMemberAction(claimId, targetUuid, "ban");
                
                Player onlineTarget = Bukkit.getPlayer(targetUuid);
                if (onlineTarget != null && isPlayerInClaim(onlineTarget, claim)) {
                    Location expulsion = plugin.getSettings().getExpulsionLocation();
                    if (expulsion != null) {
                        onlineTarget.teleport(expulsion);
                        onlineTarget.sendMessage("§c[SCS] §f您已被从该领地封禁并传送离开");
                    }
                }
                break;
            case "unban":
                claim.removeBan(targetUuid);
                plugin.getMain().saveClaim(claim);
                broadcastMemberAction(claimId, targetUuid, "unban");
                break;
        }
    }

    private void handleSettingsUpdate(Player player, UUID playerId, String[] parts) {
        if (parts.length < 4) return;

        UUID claimId = UUID.fromString(parts[1]);
        String permissionType = parts[2];
        String permissionKey = parts[3];
        boolean value = Boolean.parseBoolean(parts[4]);

        Claim claim = plugin.getMain().getClaim(claimId);
        if (claim == null || !claim.getOwner().equals(player.getName())) {
            sendErrorResult(player, "无权修改此领地设置");
            return;
        }

        claim.updatePermission(permissionType, permissionKey, value);
        plugin.getMain().saveClaim(claim);

        broadcastSettingsUpdate(claimId, permissionType, permissionKey, value);
    }

    private void handleLocationSync(Player player, UUID playerId, String[] parts) {
        if (parts.length < 4) return;

        double x = Double.parseDouble(parts[1]);
        double y = Double.parseDouble(parts[2]);
        double z = Double.parseDouble(parts[3]);
        String worldName = parts.length > 4 ? parts[4] : player.getWorld().getName();

        List<int[]> nearbyChunks = getNearbyClaimChunks(x, z, worldName);
        sendBorderVisualization(player, nearbyChunks);
    }

    private boolean createClaimForPlayer(Player player, String worldName, int chunkX, int chunkZ) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return false;

        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        Claim existingClaim = plugin.getMain().getClaimAtChunk(chunk);
        
        if (existingClaim != null) return false;

        Set<Chunk> chunks = new HashSet<>();
        chunks.add(chunk);

        UUID newClaimId = UUID.randomUUID();
        Location centerLoc = new Location(world, chunkX * 16 + 8, 64, chunkZ * 16 + 8);

        Claim newClaim = new Claim(
            newClaimId,
            chunks,
            player.getName(),
            new HashSet<>(),
            centerLoc,
            player.getName() + "的领地",
            "",
            plugin.getMain().getDefaultPermissions(),
            false,
            0L,
            new HashSet<>(),
            0
        );

        boolean economyCheck = true;
        if (plugin.getSettings().getBooleanSetting("economy") &&
            plugin.getVault() != null) {

            double cost = calculateClaimCost(chunks.size());
            if (plugin.getVault().getPlayerBalance(player.getName()) < cost) {
                player.sendMessage("§c[SCS] §f余额不足！需要: $" + cost);
                return false;
            }
            plugin.getVault().removePlayerBalance(player.getName(), cost);
            player.sendMessage("§a[SCS] §f已扣除: $" + cost);
        }

        plugin.getMain().addClaim(newClaim);
        plugin.getMain().saveClaim(newClaim);

        syncClaimsToAllPlayers();
        return true;
    }

    private boolean deleteClaimForPlayer(Player player, String claimIdStr) {
        UUID claimId = claimIdStr != null ? UUID.fromString(claimIdStr) : null;
        Claim claimToDel = claimId != null ? plugin.getMain().getClaim(claimId) : null;
        
        if (claimToDel == null) {
            Location loc = player.getLocation();
            claimToDel = plugin.getMain().getClaimAtLocation(loc);
        }

        if (claimToDel == null || !claimToDel.getOwner().equals(player.getName())) {
            return false;
        }

        plugin.getMain().removeClaim(claimToDel.getUUID());
        syncClaimsToAllPlayers();
        return true;
    }

    private boolean expandClaimForPlayer(Player player, String worldName, int chunkX, int chunkZ) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return false;

        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        Location loc = player.getLocation();
        Claim currentClaim = plugin.getMain().getClaimAtLocation(loc);

        if (currentClaim == null || !currentClaim.getOwner().equals(player.getName())) {
            return false;
        }

        Claim otherClaim = plugin.getMain().getClaimAtChunk(chunk);
        if (otherClaim != null && otherClaim.getId() != currentClaim.getId()) {
            return false;
        }

        boolean economyCheck = true;
        if (plugin.getSettings().getBooleanSetting("economy") &&
            plugin.getVault() != null) {

            double cost = calculateChunkCost();
            if (plugin.getVault().getPlayerBalance(player.getName()) < cost) {
                player.sendMessage("§c[SCS] §f余额不足！扩展费用: $" + cost);
                return false;
            }
            plugin.getVault().removePlayerBalance(player.getName(), cost);
            player.sendMessage("§a[SCS] §f已扣除扩展费用: $" + cost);
        }

        currentClaim.addChunk(chunk);
        plugin.getMain().saveClaim(currentClaim);
        syncClaimsToAllPlayers();

        return true;
    }

    private double calculateClaimCost(int chunkCount) {
        double baseCost = plugin.getMain().getDoubleSetting("default", "claim-cost", 1000.0);
        double multiplier = plugin.getMain().getDoubleSetting("default", "claim-cost-multiplier", 1.0);
        return baseCost * chunkCount * multiplier;
    }

    private double calculateChunkCost() {
        double baseCost = plugin.getMain().getDoubleSetting("default", "chunk-cost", 100.0);
        double multiplier = plugin.getMain().getDoubleSetting("default", "chunk-cost-multiplier", 1.0);
        return baseCost * multiplier;
    }

    private List<int[]> getNearbyClaimChunks(double x, double z, String worldName) {
        List<int[]> result = new ArrayList<>();
        int viewDistance = 10;
        int centerChunkX = (int) Math.floor(x / 16.0);
        int centerChunkZ = (int) Math.floor(z / 16.0);

        for (Claim claim : plugin.getMain().getAllClaims()) {
            if (!claim.getLocation().getWorld().getName().equals(worldName)) continue;

            for (Chunk chunk : claim.getChunks()) {
                int cx = chunk.getX();
                int cz = chunk.getZ();
                
                if (Math.abs(cx - centerChunkX) <= viewDistance &&
                    Math.abs(cz - centerChunkZ) <= viewDistance) {
                    result.add(new int[]{cx, cz});
                }
            }
        }

        return result;
    }

    private boolean isPlayerInClaim(Player player, Claim claim) {
        Location loc = player.getLocation();
        if (!loc.getWorld().equals(claim.getLocation().getWorld())) return false;

        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;

        for (Chunk chunk : claim.getChunks()) {
            if (chunk.getX() == chunkX && chunk.getZ() == chunkZ) {
                return true;
            }
        }
        return false;
    }

    private void syncClaimsToPlayer(Player player) {
        ByteArrayDataOutput output = ByteStreams.newDataOutput();

        output.writeUTF("sync_claims");

        List<Claim> allClaims = new ArrayList<>(plugin.getMain().getAllClaims());
        output.writeUTF(String.valueOf(allClaims.size()));

        for (Claim claim : allClaims) {
            serializeClaim(output, claim);
        }

        player.sendPluginMessage(plugin, CHANNEL, output.toByteArray());
    }

    private void syncClaimsToAllPlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isFabricClient(player)) {
                syncClaimsToPlayer(player);
            }
        }
    }

    private void serializeClaim(ByteArrayDataOutput output, Claim claim) {
        output.writeUTF(String.valueOf(claim.getId()));
        output.writeUTF(claim.getUUID().toString());
        output.writeUTF(claim.getOwner());

        output.writeUTF(claim.getName() != null ? claim.getName() : "");
        output.writeUTF(claim.getDescription() != null ? claim.getDescription() : "");

        Set<Chunk> chunks = claim.getChunks();
        output.writeInt(chunks.size());
        for (Chunk chunk : chunks) {
            output.writeInt(chunk.getX());
            output.writeInt(chunk.getZ());
        }

        output.writeUTF(claim.getLocation().getWorld().getName());

        Set<UUID> members = claim.getMembers();
        output.writeInt(members.size());
        for (UUID member : members) {
            output.writeUTF(member.toString());
            OfflinePlayer offline = Bukkit.getOfflinePlayer(member);
            output.writeUTF(offline.getName() != null ? offline.getName() : "Unknown");
        }

        Set<UUID> bans = claim.getBans();
        output.writeInt(bans.size());
        for (UUID ban : bans) {
            output.writeUTF(ban.toString());
        }

        output.writeBoolean(claim.getSale());
        output.writeDouble(claim.getPrice() / 100.0);

        Map<String, LinkedHashMap<String, Boolean>> perms = claim.getPermissions();
        output.writeInt(perms.size());
        perms.forEach((role, rolePerms) -> {
            output.writeUTF(role);
            output.writeInt(rolePerms.size());
            rolePerms.forEach((key, value) -> {
                output.writeUTF(key);
                output.writeBoolean(value);
            });
        });
    }

    private void sendHandshakeResponse(Player player, boolean success) {
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeUTF("handshake_response");
        output.writeBoolean(success);
        player.sendPluginMessage(plugin, CHANNEL, output.toByteArray());
    }

    private void sendClaimResult(Player player, boolean success, String message) {
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeUTF("claim_result");
        output.writeBoolean(success);
        output.writeUTF(message);
        player.sendPluginMessage(plugin, CHANNEL, output.toByteArray());
    }

    private void sendErrorResult(Player player, String errorMessage) {
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeUTF("error");
        output.writeUTF(errorMessage);
        player.sendPluginMessage(plugin, CHANNEL, output.toByteArray());
    }

    private void broadcastClaimUpdate(UUID claimId, String name, String description,
                                     boolean forSale, double price) {
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeUTF("update_claim");
        output.writeUTF(claimId.toString());
        output.writeUTF(name);
        output.writeUTF(description);
        output.writeBoolean(forSale);
        output.writeDouble(price);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isFabricClient(player)) {
                player.sendPluginMessage(plugin, CHANNEL, output.toByteArray());
            }
        }
    }

    private void broadcastMemberAction(UUID claimId, UUID targetPlayer, String action) {
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeUTF("member_action");
        output.writeUTF(claimId.toString());
        output.writeUTF(targetPlayer.toString());
        output.writeUTF(action);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isFabricClient(player)) {
                player.sendPluginMessage(plugin, CHANNEL, output.toByteArray());
            }
        }
    }

    private void broadcastSettingsUpdate(UUID claimId, String permType, String permKey, boolean value) {
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeUTF("settings_update");
        output.writeUTF(claimId.toString());
        output.writeUTF(permType);
        output.writeUTF(permKey);
        output.writeBoolean(value);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isFabricClient(player)) {
                player.sendPluginMessage(plugin, CHANNEL, output.toByteArray());
            }
        }
    }

    private void sendBorderVisualization(Player player, List<int[]> chunks) {
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeUTF("border_visualization");
        output.writeInt(chunks.size());
        for (int[] chunk : chunks) {
            output.writeInt(chunk[0]);
            output.writeInt(chunk[1]);
        }
        output.writeInt(0);
        output.writeInt(255);
        output.writeInt(128);
        output.writeInt(200);

        player.sendPluginMessage(plugin, CHANNEL, output.toByteArray());
    }

    public boolean isFabricClient(Player player) {
        return fabricPlayers.containsKey(player.getUniqueId());
    }

    public void unregister() {
        try {
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
            plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
            fabricPlayers.clear();
            plugin.info("§e[FabricNetwork] §fNetwork channel unregistered");
        } catch (Exception e) {
            plugin.info("§c[FabricNetwork] §fError unregistering: " + e.getMessage());
        }
    }
}
