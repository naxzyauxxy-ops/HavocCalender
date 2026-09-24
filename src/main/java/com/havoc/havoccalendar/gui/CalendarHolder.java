package com.havoc.havoccalendar.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Marker holder used to identify HavocCalendar inventories safely.
 * Comparing holders is far more reliable than comparing inventory titles.
 */
public final class CalendarHolder implements InventoryHolder {

    private final UUID viewer;
    private Inventory inventory;

    CalendarHolder(UUID viewer) {
        this.viewer = viewer;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public UUID viewer() {
        return viewer;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
