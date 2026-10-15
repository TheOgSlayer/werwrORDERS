package com.sellgui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SellGUI extends JavaPlugin implements CommandExecutor {

    private final Map<Material, Double> prices = new EnumMap<>(Material.class);
    private Economy economy;

    @Override
    public void onEnable() {
        saveResource("prices.yml", false);
        loadPrices();

        if (getEconomy() == null) {
            getLogger().severe("No Vault economy found (is EssentialsX installed?). Disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (getCommand("sell") != null) {
            getCommand("sell").setExecutor(this);
        }

        OrderSystem orders = new OrderSystem(this);
        if (getCommand("order") != null) {
            getCommand("order").setExecutor(orders);
        }
        Bukkit.getPluginManager().registerEvents(orders, this);
    }

    private void loadPrices() {
        prices.clear();
        File file = new File(getDataFolder(), "prices.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String key : yml.getKeys(false)) {
            Material mat = Material.matchMaterial(key);
            if (mat == null) {
                getLogger().warning("prices.yml: unknown material '" + key + "'");
                continue;
            }
            double price = yml.getDouble(key, -1);
            if (price < 0) {
                getLogger().warning("prices.yml: invalid price for '" + key + "'");
                continue;
            }
            prices.put(mat, price);
        }
        getLogger().info("Loaded " + prices.size() + " sell prices.");
    }

    Economy getEconomy() {
        if (economy == null) {
            RegisteredServiceProvider<Economy> rsp =
                    getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) {
                economy = rsp.getProvider();
            }
        }
        return economy;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /sell.");
            return true;
        }
        loadPrices();
        openDialog(player);
        return true;
    }

    // ---------------------------------------------------------------- inventory helpers

    private List<ItemStack> sellableStacks(Player player) {
        List<ItemStack> list = new ArrayList<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && !item.getType().isAir() && prices.containsKey(item.getType())) {
                list.add(item);
            }
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (!off.getType().isAir() && prices.containsKey(off.getType())) {
            list.add(off);
        }
        return list;
    }

    private Map<Material, Integer> countItems(Player player) {
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        for (ItemStack item : sellableStacks(player)) {
            counts.merge(item.getType(), item.getAmount(), Integer::sum);
        }
        return counts;
    }

    private double valueOf(Material mat, int amount) {
        return prices.getOrDefault(mat, 0.0) * amount;
    }

    static String fmt(double amount) {
        return String.format(Locale.US, "%,.2f", amount);
    }

    static String pretty(Material mat) {
        String[] parts = mat.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    static Component money(double amount) {
        return Component.text("$", NamedTextColor.GREEN)
                .append(Component.text(fmt(amount), NamedTextColor.WHITE));
    }

    // ---------------------------------------------------------------- dialog

    /** "JALEBI" in gold/orange followed by "SMP" in white. */
    static Component title() {
        return Component.text("JALEBI", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(" SMP", NamedTextColor.WHITE, TextDecoration.BOLD));
    }

    ActionButton button(Component label, DialogAction action) {
        return ActionButton.builder(label).width(220).action(action).build();
    }

    DialogAction click(Runnable task) {
        return DialogAction.customClick(
                (view, audience) -> Bukkit.getScheduler().runTask(this, task),
                ClickCallback.Options.builder().build());
    }

    private void openDialog(Player player) {
        Map<Material, Integer> counts = countItems(player);
        Component title = title();

        if (counts.isEmpty()) {
            Dialog empty = Dialog.create(f -> f.empty()
                    .base(DialogBase.builder(title)
                            .body(List.of(DialogBody.plainMessage(
                                    Component.text("You have nothing to sell.", NamedTextColor.GRAY))))
                            .canCloseWithEscape(true)
                            .build())
                    .type(DialogType.notice(ActionButton.builder(Component.text("Close")).width(220).build())));
            player.showDialog(empty);
            return;
        }

        double total = 0;
        for (Map.Entry<Material, Integer> e : counts.entrySet()) {
            total += valueOf(e.getKey(), e.getValue());
        }

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text("Click an item to choose how many to sell", NamedTextColor.GRAY)));
        body.add(DialogBody.plainMessage(Component.text("Total: ", NamedTextColor.WHITE).append(money(total))));

        List<ActionButton> buttons = new ArrayList<>();
        for (Map.Entry<Material, Integer> e : counts.entrySet()) {
            Material mat = e.getKey();
            Component label = Component.text(pretty(mat), NamedTextColor.WHITE);
            buttons.add(button(label, click(() -> openItemDialog(player, mat, null))));
        }
        buttons.add(button(Component.text("Sell All  ", NamedTextColor.WHITE).append(money(total)),
                click(() -> sell(player, null))));

        // Esc triggers this button's action, so it must NOT sell anything
        ActionButton close = ActionButton.builder(Component.text("Close")).width(220).build();

        Dialog dialog = Dialog.create(f -> f.empty()
                .base(DialogBase.builder(title)
                        .body(body)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons, close, 1)));
        player.showDialog(dialog);
    }

    // ---------------------------------------------------------------- per-item dialog

    private int countOf(Player player, Material mat) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == mat) count += item.getAmount();
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (off.getType() == mat) count += off.getAmount();
        return count;
    }

    private void openItemDialog(Player player, Material mat, Component error) {
        int have = countOf(player, mat);
        if (have <= 0 || !prices.containsKey(mat)) {
            openDialog(player);
            return;
        }
        double price = prices.get(mat);

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(pretty(mat), NamedTextColor.GOLD)));
        body.add(DialogBody.plainMessage(
                Component.text("You have " + have, NamedTextColor.WHITE)));
        body.add(DialogBody.plainMessage(
                Component.text("Price each: ", NamedTextColor.GRAY).append(money(price))));
        if (error != null) {
            body.add(DialogBody.plainMessage(error));
        }

        DialogInput amountInput = DialogInput.text("amount", Component.text("Enter amount to sell"))
                .width(220)
                .initial("1")
                .maxLength(9)
                .build();

        ActionButton sellButton = button(Component.text("Sell", NamedTextColor.WHITE),
                DialogAction.customClick((view, audience) -> {
                    String raw = view.getText("amount"); // read while the response is valid
                    Bukkit.getScheduler().runTask(this, () -> sellFromInput(player, mat, raw));
                }, ClickCallback.Options.builder().build()));

        ActionButton sellAllButton = button(
                Component.text("Sell all of this item  ", NamedTextColor.WHITE).append(money(price * have)),
                click(() -> sellAmount(player, mat, -1)));

        ActionButton back = ActionButton.builder(Component.text("Back"))
                .width(220)
                .action(click(() -> openDialog(player)))
                .build();

        Dialog dialog = Dialog.create(f -> f.empty()
                .base(DialogBase.builder(title())
                        .body(body)
                        .inputs(List.of(amountInput))
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(List.of(sellButton, sellAllButton), back, 1)));
        player.showDialog(dialog);
    }

    private void sellFromInput(Player player, Material mat, String raw) {
        int have = countOf(player, mat);
        if (have <= 0) {
            openDialog(player);
            return;
        }

        long n;
        try {
            n = Long.parseLong(raw == null ? "" : raw.trim());
        } catch (NumberFormatException ex) {
            openItemDialog(player, mat, Component.text("Enter a whole number.", NamedTextColor.RED));
            return;
        }
        if (n < 1) {
            openItemDialog(player, mat, Component.text("Amount must be at least 1.", NamedTextColor.RED));
            return;
        }
        if (n > have) {
            openItemDialog(player, mat, Component.text(
                    "You only have " + have + " " + pretty(mat) + ".", NamedTextColor.RED));
            return;
        }
        sellAmount(player, mat, (int) n);
    }

    /** @param amount how many to sell, or -1 for all of that item. */
    private void sellAmount(Player player, Material mat, int amount) {
        Economy eco = getEconomy();
        if (eco == null || !player.isOnline()) return;

        int have = countOf(player, mat);
        if (have <= 0) {
            openDialog(player);
            return;
        }
        int n = amount < 0 ? have : amount;
        if (n > have) {
            openItemDialog(player, mat, Component.text(
                    "You only have " + have + " " + pretty(mat) + ".", NamedTextColor.RED));
            return;
        }

        double total = valueOf(mat, n);
        EconomyResponse r = eco.depositPlayer(player, total);
        if (!r.transactionSuccess()) {
            getLogger().warning("Deposit failed for " + player.getName() + ": " + r.errorMessage);
            player.sendMessage(Component.text("Sale failed: " + r.errorMessage, NamedTextColor.RED));
            return;
        }

        removeItems(player, mat, n);
        player.sendMessage(Component.text("+", NamedTextColor.WHITE).append(money(total)));

        // shows this item's dialog again, or the main list if none are left
        openItemDialog(player, mat, null);
    }

    private void removeItems(Player player, Material mat, int amount) {
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != mat) continue;
            int take = Math.min(remaining, item.getAmount());
            if (take >= item.getAmount()) {
                contents[i] = null;
            } else {
                item.setAmount(item.getAmount() - take);
            }
            remaining -= take;
        }
        player.getInventory().setStorageContents(contents);

        if (remaining > 0) {
            ItemStack off = player.getInventory().getItemInOffHand();
            if (off.getType() == mat) {
                int take = Math.min(remaining, off.getAmount());
                if (take >= off.getAmount()) {
                    player.getInventory().setItemInOffHand(null);
                } else {
                    off.setAmount(off.getAmount() - take);
                    player.getInventory().setItemInOffHand(off);
                }
            }
        }
    }

    // ---------------------------------------------------------------- selling

    /** @param only the material to sell, or null to sell everything sellable. */
    private void sell(Player player, Material only) {
        Economy eco = getEconomy();
        if (eco == null || !player.isOnline()) return;

        // work out what we are selling from the CURRENT inventory
        double total = 0;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (ItemStack item : contents) {
            if (item == null || item.getType().isAir()) continue;
            if (!prices.containsKey(item.getType())) continue;
            if (only != null && item.getType() != only) continue;
            total += valueOf(item.getType(), item.getAmount());
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        boolean sellOff = !off.getType().isAir() && prices.containsKey(off.getType())
                && (only == null || off.getType() == only);
        if (sellOff) {
            total += valueOf(off.getType(), off.getAmount());
        }

        if (total <= 0) {
            openDialog(player);
            return;
        }

        EconomyResponse r = eco.depositPlayer(player, total);
        if (!r.transactionSuccess()) {
            getLogger().warning("Deposit failed for " + player.getName() + ": " + r.errorMessage);
            player.sendMessage(Component.text("Sale failed: " + r.errorMessage, NamedTextColor.RED));
            return;
        }

        // payment went through -> remove the items
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType().isAir()) continue;
            if (!prices.containsKey(item.getType())) continue;
            if (only != null && item.getType() != only) continue;
            contents[i] = null;
        }
        player.getInventory().setStorageContents(contents);
        if (sellOff) {
            player.getInventory().setItemInOffHand(null);
        }

        player.sendMessage(Component.text("+", NamedTextColor.WHITE).append(money(total)));

        // refresh the dialog with what is left
        openDialog(player);
    }
}
