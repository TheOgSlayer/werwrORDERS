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
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Buy orders: a player asks for X of an item for a total price.
 * The money is taken immediately and refunded if the order is cancelled.
 * Whoever hands over the items receives the money.
 */
public class OrderSystem implements CommandExecutor, Listener {

    static final int MAX_ORDERS = 5;
    static final int MAX_AMOUNT = 2304;               // 36 slots * 64
    static final double MAX_PRICE = 1_000_000_000_000.0;

    private record Order(UUID id, UUID owner, String ownerName, Material material,
                         int amount, double price, long created) {}

    private final SellGUI plugin;
    private final File file;
    private final Map<UUID, Order> orders = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> pending = new HashMap<>();

    public OrderSystem(SellGUI plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "orders.yml");
        load();
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection os = y.getConfigurationSection("orders");
        if (os != null) {
            for (String key : os.getKeys(false)) {
                try {
                    ConfigurationSection c = os.getConfigurationSection(key);
                    if (c == null) continue;
                    Material mat = Material.matchMaterial(c.getString("material", ""));
                    if (mat == null) continue;
                    UUID id = UUID.fromString(key);
                    orders.put(id, new Order(id, UUID.fromString(c.getString("owner", "")),
                            c.getString("ownerName", "Unknown"), mat,
                            c.getInt("amount"), c.getDouble("price"), c.getLong("created")));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("orders.yml: skipped invalid order '" + key + "'");
                }
            }
        }

        ConfigurationSection ps = y.getConfigurationSection("pending");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    List<ItemStack> list = new ArrayList<>();
                    for (Object o : ps.getList(key, new ArrayList<>())) {
                        if (o instanceof ItemStack is) list.add(is);
                    }
                    if (!list.isEmpty()) pending.put(uuid, list);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        plugin.getLogger().info("Loaded " + orders.size() + " open orders.");
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Order o : orders.values()) {
            String k = "orders." + o.id();
            y.set(k + ".owner", o.owner().toString());
            y.set(k + ".ownerName", o.ownerName());
            y.set(k + ".material", o.material().name());
            y.set(k + ".amount", o.amount());
            y.set(k + ".price", o.price());
            y.set(k + ".created", o.created());
        }
        for (Map.Entry<UUID, List<ItemStack>> e : pending.entrySet()) {
            y.set("pending." + e.getKey(), e.getValue());
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save orders.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ command / join

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /order.");
            return true;
        }
        openMain(player);
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        List<ItemStack> items = pending.remove(player.getUniqueId());
        if (items == null) return;
        save();
        // small delay so the player is fully loaded in
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            give(player, items);
            player.sendMessage(Component.text("Your order was filled while you were away. "
                    + "The items were added to your inventory.", NamedTextColor.GREEN));
        }, 40L);
    }

    // ------------------------------------------------------------------ dialog helpers

    private ActionButton back(Runnable action) {
        return ActionButton.builder(Component.text("Back")).width(220).action(plugin.click(action)).build();
    }

    private ActionButton closeButton() {
        // Esc runs the exit button's action, so Close does nothing but close
        return ActionButton.builder(Component.text("Close")).width(220).build();
    }

    private void show(Player player, List<DialogBody> body, List<DialogInput> inputs,
                      List<ActionButton> actions, ActionButton exit) {
        Dialog dialog = Dialog.create(f -> f.empty()
                .base(DialogBase.builder(SellGUI.title())
                        .body(body)
                        .inputs(inputs)
                        .canCloseWithEscape(true)
                        .build())
                .type(actions.isEmpty()
                        ? DialogType.notice(exit)
                        : DialogType.multiAction(actions, exit, 1)));
        player.showDialog(dialog);
    }

    private static DialogBody line(String text, NamedTextColor color) {
        return DialogBody.plainMessage(Component.text(text, color));
    }

    private static DialogBody line(Component component) {
        return DialogBody.plainMessage(component);
    }

    private int ordersOf(UUID owner) {
        int n = 0;
        for (Order o : orders.values()) {
            if (o.owner().equals(owner)) n++;
        }
        return n;
    }

    private static String itemLabel(Order o) {
        return SellGUI.pretty(o.material()) + " x" + o.amount();
    }

    // ------------------------------------------------------------------ main menu

    private void openMain(Player player) {
        int mine = ordersOf(player.getUniqueId());
        List<DialogBody> body = List.of(
                line("Buy Orders", NamedTextColor.GOLD),
                line("Your orders: " + mine + "/" + MAX_ORDERS, NamedTextColor.GRAY));

        List<ActionButton> actions = List.of(
                plugin.button(Component.text("Browse Orders"), plugin.click(() -> openBrowse(player))),
                plugin.button(Component.text("Create Order"),
                        plugin.click(() -> {
                            drafts.remove(player.getUniqueId());
                            openCreate(player, null);
                        })),
                plugin.button(Component.text("My Orders (" + mine + "/" + MAX_ORDERS + ")"),
                        plugin.click(() -> openMyOrders(player))));
        show(player, body, List.of(), actions, closeButton());
    }

    // ------------------------------------------------------------------ browse & fill

    private void openBrowse(Player player) {
        List<Order> list = new ArrayList<>();
        for (Order o : orders.values()) {
            if (!o.owner().equals(player.getUniqueId())) list.add(o);
        }
        Collections.reverse(list); // newest first
        if (list.size() > 50) list = list.subList(0, 50);

        List<DialogBody> body = new ArrayList<>();
        body.add(line(list.isEmpty() ? "No orders from other players right now."
                : "Click an order to fill it", NamedTextColor.GRAY));

        List<ActionButton> actions = new ArrayList<>();
        for (Order o : list) {
            UUID id = o.id();
            Component label = Component.text(itemLabel(o) + "  ", NamedTextColor.WHITE)
                    .append(SellGUI.money(o.price()));
            actions.add(plugin.button(label, plugin.click(() -> openFill(player, id, null))));
        }
        show(player, body, List.of(), actions, back(() -> openMain(player)));
    }

    private void openFill(Player player, UUID id, Component error) {
        Order o = orders.get(id);
        if (o == null) {
            player.sendMessage(Component.text("That order no longer exists.", NamedTextColor.RED));
            openBrowse(player);
            return;
        }
        int have = countPlain(player, o.material());

        List<DialogBody> body = new ArrayList<>();
        body.add(line(SellGUI.pretty(o.material()), NamedTextColor.GOLD));
        body.add(line("Wants: " + o.amount(), NamedTextColor.WHITE));
        body.add(line(Component.text("Pays: ", NamedTextColor.WHITE).append(SellGUI.money(o.price()))));
        body.add(line("Ordered by: " + o.ownerName(), NamedTextColor.GRAY));
        body.add(line("You have: " + have, have >= o.amount() ? NamedTextColor.GREEN : NamedTextColor.RED));
        if (error != null) body.add(line(error));

        List<ActionButton> actions = List.of(
                plugin.button(Component.text("Fill Order  ", NamedTextColor.WHITE).append(SellGUI.money(o.price())),
                        plugin.click(() -> fill(player, id))));
        show(player, body, List.of(), actions, back(() -> openBrowse(player)));
    }

    private void fill(Player player, UUID id) {
        Order o = orders.get(id);
        if (o == null) {
            player.sendMessage(Component.text("That order no longer exists.", NamedTextColor.RED));
            openBrowse(player);
            return;
        }
        if (o.owner().equals(player.getUniqueId())) {
            openFill(player, id, Component.text("You can't fill your own order.", NamedTextColor.RED));
            return;
        }
        int have = countPlain(player, o.material());
        if (have < o.amount()) {
            openFill(player, id, Component.text("You need " + (o.amount() - have) + " more "
                    + SellGUI.pretty(o.material()) + ".", NamedTextColor.RED));
            return;
        }
        Economy eco = plugin.getEconomy();
        if (eco == null) return;

        EconomyResponse r = eco.depositPlayer(player, o.price());
        if (!r.transactionSuccess()) {
            openFill(player, id, Component.text("Payment failed: " + r.errorMessage, NamedTextColor.RED));
            return;
        }

        List<ItemStack> taken = removeItems(player, o.material(), o.amount());
        orders.remove(id);
        deliver(o, player.getName(), taken);
        save();

        player.sendMessage(Component.text("+", NamedTextColor.WHITE).append(SellGUI.money(o.price())));
        openBrowse(player);
    }

    /** Gives the handed-over items to the order owner (now, or when they next join). */
    private void deliver(Order o, String fillerName, List<ItemStack> items) {
        Player owner = Bukkit.getPlayer(o.owner());
        if (owner != null) {
            give(owner, items);
            owner.sendMessage(Component.text("Your order for " + itemLabel(o) + " was filled by "
                    + fillerName + ".", NamedTextColor.GREEN));
        } else {
            pending.computeIfAbsent(o.owner(), k -> new ArrayList<>()).addAll(items);
        }
    }

    // ------------------------------------------------------------------ create

    /** What the player has filled in so far while creating an order. */
    private static final class Draft {
        Material material;
        String amount = "";
        String price = "";
    }

    private final Map<UUID, Draft> drafts = new HashMap<>();
    private List<Material> allItems;

    private Draft draftOf(Player player) {
        return drafts.computeIfAbsent(player.getUniqueId(), k -> {
            Draft d = new Draft();
            Material held = player.getInventory().getItemInMainHand().getType();
            if (!held.isAir() && held.isItem()) d.material = held;
            return d;
        });
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private void openCreate(Player player, Component error) {
        Draft d = draftOf(player);

        List<DialogBody> body = new ArrayList<>();
        body.add(line("The money is taken now and refunded if you cancel.", NamedTextColor.GRAY));
        body.add(line("Your orders: " + ordersOf(player.getUniqueId()) + "/" + MAX_ORDERS, NamedTextColor.GRAY));
        body.add(line(Component.text("Item: ", NamedTextColor.WHITE).append(d.material == null
                ? Component.text("not selected", NamedTextColor.RED)
                : Component.text(SellGUI.pretty(d.material), NamedTextColor.GOLD))));
        if (error != null) body.add(line(error));

        List<DialogInput> inputs = List.of(
                DialogInput.text("amount", Component.text("Amount"))
                        .width(220).initial(d.amount).maxLength(6).build(),
                DialogInput.text("price", Component.text("Total price you will pay"))
                        .width(220).initial(d.price).maxLength(16).build());

        ActionButton choose = plugin.button(
                Component.text(d.material == null ? "Choose Item" : "Change Item"),
                DialogAction.customClick((view, audience) -> {
                    String a = nz(view.getText("amount"));
                    String p = nz(view.getText("price"));
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Draft dr = draftOf(player);
                        dr.amount = a;
                        dr.price = p;
                        openPicker(player, "", 0);
                    });
                }, ClickCallback.Options.builder().build()));

        ActionButton place = plugin.button(Component.text("Place Order"),
                DialogAction.customClick((view, audience) -> {
                    String a = nz(view.getText("amount"));
                    String p = nz(view.getText("price"));
                    Bukkit.getScheduler().runTask(plugin, () -> placeOrder(player, a, p));
                }, ClickCallback.Options.builder().build()));

        show(player, body, inputs, List.of(choose, place), back(() -> {
            drafts.remove(player.getUniqueId());
            openMain(player);
        }));
    }

    // ------------------------------------------------------------------ item picker

    private static final int PAGE_SIZE = 15;

    private List<Material> items() {
        if (allItems == null) {
            List<Material> list = new ArrayList<>();
            for (Material m : Material.values()) {
                if (!m.isLegacy() && m.isItem() && !m.isAir()) list.add(m);
            }
            list.sort((x, y) -> x.name().compareTo(y.name()));
            allItems = list;
        }
        return allItems;
    }

    private List<Material> search(String query) {
        String[] words = query.toLowerCase(Locale.ROOT).trim().replace('_', ' ').split("\\s+");
        List<Material> out = new ArrayList<>();
        for (Material m : items()) {
            String name = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
            boolean ok = true;
            for (String w : words) {
                if (!w.isEmpty() && !name.contains(w)) {
                    ok = false;
                    break;
                }
            }
            if (ok) out.add(m);
        }
        return out;
    }

    private void openPicker(Player player, String queryRaw, int pageRaw) {
        final String query = queryRaw == null ? "" : queryRaw.trim();
        List<Material> results = search(query);
        int pages = Math.max(1, (results.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        final int page = Math.max(0, Math.min(pageRaw, pages - 1));

        List<DialogBody> body = new ArrayList<>();
        body.add(line(results.isEmpty() ? "No items found."
                : results.size() + " items - page " + (page + 1) + "/" + pages, NamedTextColor.GRAY));

        List<DialogInput> inputs = List.of(
                DialogInput.text("search", Component.text("Search items"))
                        .width(220).initial(query).maxLength(32).build());

        List<ActionButton> actions = new ArrayList<>();
        actions.add(plugin.button(Component.text("Search"),
                DialogAction.customClick((view, audience) -> {
                    String q = nz(view.getText("search"));
                    Bukkit.getScheduler().runTask(plugin, () -> openPicker(player, q, 0));
                }, ClickCallback.Options.builder().build())));

        int from = page * PAGE_SIZE;
        int to = Math.min(results.size(), from + PAGE_SIZE);
        for (Material m : results.subList(from, to)) {
            actions.add(plugin.button(Component.text(SellGUI.pretty(m), NamedTextColor.WHITE),
                    plugin.click(() -> {
                        draftOf(player).material = m;
                        openCreate(player, null);
                    })));
        }
        if (page > 0) {
            actions.add(plugin.button(Component.text("Previous Page", NamedTextColor.YELLOW),
                    plugin.click(() -> openPicker(player, query, page - 1))));
        }
        if (page < pages - 1) {
            actions.add(plugin.button(Component.text("Next Page", NamedTextColor.YELLOW),
                    plugin.click(() -> openPicker(player, query, page + 1))));
        }

        show(player, body, inputs, actions, back(() -> openCreate(player, null)));
    }

    // ------------------------------------------------------------------ placing

    private void placeOrder(Player player, String amountRaw, String priceRaw) {
        Draft d = draftOf(player);
        d.amount = nz(amountRaw);
        d.price = nz(priceRaw);

        if (ordersOf(player.getUniqueId()) >= MAX_ORDERS) {
            openCreate(player, err("You already have " + MAX_ORDERS + " orders. Cancel one first."));
            return;
        }
        Material mat = d.material;
        if (mat == null) {
            openCreate(player, err("Choose an item first."));
            return;
        }

        int amount;
        try {
            amount = Integer.parseInt(d.amount);
        } catch (NumberFormatException ex) {
            openCreate(player, err("Amount must be a whole number."));
            return;
        }
        if (amount < 1 || amount > MAX_AMOUNT) {
            openCreate(player, err("Amount must be between 1 and " + MAX_AMOUNT + "."));
            return;
        }

        double price;
        try {
            price = Double.parseDouble(d.price.replace(",", ""));
        } catch (NumberFormatException ex) {
            openCreate(player, err("Price must be a number."));
            return;
        }
        price = Math.round(price * 100.0) / 100.0;
        if (Double.isNaN(price) || Double.isInfinite(price) || price < 0.01 || price > MAX_PRICE) {
            openCreate(player, err("Enter a valid price."));
            return;
        }

        Economy eco = plugin.getEconomy();
        if (eco == null) return;
        if (!eco.has(player, price)) {
            openCreate(player, err("You don't have enough money."));
            return;
        }
        EconomyResponse r = eco.withdrawPlayer(player, price);
        if (!r.transactionSuccess()) {
            openCreate(player, err("Payment failed: " + r.errorMessage));
            return;
        }

        Order o = new Order(UUID.randomUUID(), player.getUniqueId(), player.getName(), mat,
                amount, price, System.currentTimeMillis());
        orders.put(o.id(), o);
        drafts.remove(player.getUniqueId());
        save();

        player.sendMessage(Component.text("Order placed: " + itemLabel(o) + " for ", NamedTextColor.GREEN)
                .append(SellGUI.money(price)));
        openMyOrders(player);
    }

    private static Component err(String text) {
        return Component.text(text, NamedTextColor.RED);
    }

    // ------------------------------------------------------------------ my orders / cancel

    private void openMyOrders(Player player) {
        List<Order> mine = new ArrayList<>();
        for (Order o : orders.values()) {
            if (o.owner().equals(player.getUniqueId())) mine.add(o);
        }

        List<DialogBody> body = new ArrayList<>();
        body.add(line(mine.isEmpty() ? "You have no open orders."
                : "Click an order to cancel it (" + mine.size() + "/" + MAX_ORDERS + ")", NamedTextColor.GRAY));

        List<ActionButton> actions = new ArrayList<>();
        for (Order o : mine) {
            UUID id = o.id();
            Component label = Component.text(itemLabel(o) + "  ", NamedTextColor.WHITE)
                    .append(SellGUI.money(o.price()));
            actions.add(plugin.button(label, plugin.click(() -> openOrderDetail(player, id))));
        }
        show(player, body, List.of(), actions, back(() -> openMain(player)));
    }

    private void openOrderDetail(Player player, UUID id) {
        Order o = orders.get(id);
        if (o == null || !o.owner().equals(player.getUniqueId())) {
            openMyOrders(player);
            return;
        }
        List<DialogBody> body = List.of(
                line(SellGUI.pretty(o.material()), NamedTextColor.GOLD),
                line("Amount wanted: " + o.amount(), NamedTextColor.WHITE),
                line(Component.text("Money held: ", NamedTextColor.WHITE).append(SellGUI.money(o.price()))));

        List<ActionButton> actions = List.of(
                plugin.button(Component.text("Cancel Order  ", NamedTextColor.WHITE).append(SellGUI.money(o.price())),
                        plugin.click(() -> cancel(player, id))));
        show(player, body, List.of(), actions, back(() -> openMyOrders(player)));
    }

    private void cancel(Player player, UUID id) {
        Order o = orders.get(id);
        if (o == null || !o.owner().equals(player.getUniqueId())) {
            openMyOrders(player);
            return;
        }
        Economy eco = plugin.getEconomy();
        if (eco == null) return;

        EconomyResponse r = eco.depositPlayer(player, o.price());
        if (!r.transactionSuccess()) {
            player.sendMessage(Component.text("Refund failed: " + r.errorMessage, NamedTextColor.RED));
            return;
        }
        orders.remove(id);
        save();

        player.sendMessage(Component.text("+", NamedTextColor.WHITE).append(SellGUI.money(o.price()))
                .append(Component.text(" (order cancelled)", NamedTextColor.GRAY)));
        openMyOrders(player);
    }

    // ------------------------------------------------------------------ inventory helpers
    // Any item of the right type counts, and the exact items handed over are given to the order owner.

    private int countPlain(Player player, Material mat) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == mat) count += item.getAmount();
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (off.getType() == mat) count += off.getAmount();
        return count;
    }

    /** Removes the items from the player and returns exactly what was removed. */
    private List<ItemStack> removeItems(Player player, Material mat, int amount) {
        List<ItemStack> removed = new ArrayList<>();
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != mat) continue;
            int take = Math.min(remaining, item.getAmount());
            ItemStack part = item.clone();
            part.setAmount(take);
            removed.add(part);
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
                ItemStack part = off.clone();
                part.setAmount(take);
                removed.add(part);
                if (take >= off.getAmount()) {
                    player.getInventory().setItemInOffHand(null);
                } else {
                    off.setAmount(off.getAmount() - take);
                    player.getInventory().setItemInOffHand(off);
                }
            }
        }
        return removed;
    }

    private void give(Player player, List<ItemStack> items) {
        for (ItemStack item : items) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
    }
}
