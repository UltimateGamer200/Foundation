package org.mineacademy.fo.model;

import java.util.Map.Entry;
import java.util.function.BiConsumer;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;
import org.mineacademy.fo.EntityUtil;

/**
 * Listens and executes events for {@link SimpleEnchantment}
 *
 * Internal use only!
 */
public final class EnchantmentListener implements Listener {

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onEntityDamage(EntityDamageByEntityEvent event) {
		final Entity damager = event.getDamager();

		if (damager instanceof LivingEntity)
			execute((LivingEntity) damager, (enchant, level) -> enchant.onDamage(level, (LivingEntity) damager, event));
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
	public void onInteract(PlayerInteractEvent event) {
		execute(event.getPlayer(), (enchant, level) -> enchant.onInteract(level, event));
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onBreakBlock(BlockBreakEvent event) {
		execute(event.getPlayer(), (enchant, level) -> enchant.onBreakBlock(level, event));
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onShoot(ProjectileLaunchEvent event) {
		final ProjectileSource projectileSource = event.getEntity().getShooter();

		if (projectileSource instanceof LivingEntity) {
			final LivingEntity shooter = (LivingEntity) projectileSource;

			execute(shooter, (enchant, level) -> enchant.onShoot(level, shooter, event));
			EntityUtil.trackHit(event.getEntity(), (hitEvent) -> execute(shooter, (enchant, level) -> enchant.onHit(level, shooter, hitEvent)));
		}
	}

	private void execute(LivingEntity source, BiConsumer<SimpleEnchantment, Integer> executer) {
		final ItemStack hand = source.getEquipment().getItemInHand();

		if (hand != null)
			for (final Entry<SimpleEnchantment, Integer> e : SimpleEnchantment.findEnchantments(hand).entrySet())
				executer.accept(e.getKey(), e.getValue());
	}
}
