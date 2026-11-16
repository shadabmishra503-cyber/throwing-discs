package com.throwingdiscs;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The flying disc. A direct port of the Bedrock script (main.js): it flies straight with no gravity,
 * ricochets off hard blocks, pierces every mob on its path, then curves home like a boomerang.
 * Soft blocks (wool, leaves, hay...) kill its momentum so it heads home, slime flings it further.
 *
 * The disc is moved to an exact position every tick (no velocity), so clients never draw it inside a wall.
 * The entity's position IS the visual centre of the disc.
 */
public class ThrowingDiscEntity extends Entity implements ItemSupplier {
	private static final EntityDataAccessor<ItemStack> DATA_ITEM =
			SynchedEntityData.defineId(ThrowingDiscEntity.class, EntityDataSerializers.ITEM_STACK);

	// ---- tuning (same values as the Bedrock add-on) ----------------------------------------
	private static final int OUT_TICKS = 12; // straight-line flight before turning home (~16 blocks)
	private static final int BOUNCE_BONUS_TICKS = 5; // every ricochet extends the outward flight
	private static final int SLIME_BONUS_TICKS = 10; // slime flings it further and doesn't count as a bounce
	private static final int MAX_BOUNCES = 6;
	private static final double RETURN_START_SPEED = 0.45;
	private static final double RETURN_PULL = 0.3; // homing pull per tick on the way back
	private static final double RETURN_DRAG = 0.88;
	private static final double RETURN_MAX_SPEED = 1.7;
	private static final int RETURN_STALL_TICKS = 20; // no progress home for this long = stuck, so it drops
	private static final double CATCH_RADIUS = 1.5;
	private static final int MIN_CATCH_AGE = 5;
	private static final int MAX_AGE = 400; // safety net: 20 s in the air and it drops
	private static final double MAX_OWNER_DISTANCE = 96;
	private static final int MAX_HITS_PER_TARGET = 3; // per throw (out, ricochet legs, return)
	private static final double DISC_RADIUS = 0.35;
	private static final double DISC_HALF_THICK = 0.06;
	private static final double SKIN = 0.04; // gap kept between the disc and a surface it touches
	private static final int THROW_LOCKOUT = 4; // ticks
	private static final int CATCH_LOCKOUT = 5;
	private static final int OFFHAND_SLOT = 40;

	/** Leading-edge rim probes, dense toward the sides where the edge curves away fastest. */
	private static final double[] RIM_FRONT = buildRimFront();
	private static final double[] RIM_ALL = buildRimAll();

	private static double[] buildRimFront() {
		double[] list = new double[21];
		list[0] = 0;
		for (int i = 1; i <= 10; i++) {
			double a = Math.acos(1 - i / 10.0);
			list[2 * i - 1] = a;
			list[2 * i] = -a;
		}
		return list;
	}

	private static double[] buildRimAll() {
		double[] list = new double[16];
		for (int i = 0; i < 16; i++) {
			list[i] = i * Math.PI / 8;
		}
		return list;
	}

	/** player id -> game tick before which they can't throw */
	private static final Map<UUID, Long> LOCKED_UNTIL = new HashMap<>();

	// ---- state ------------------------------------------------------------------------------
	private UUID ownerId;
	private int slot = -1; // inventory slot it came from (40 = off-hand)
	private boolean creative;
	private boolean returning;
	private Vec3 dir = new Vec3(0, 0, 1);
	private int outLeft = OUT_TICKS;
	private int bounces;
	private int age;
	private double backSpeed = RETURN_START_SPEED;
	private Vec3 vel = Vec3.ZERO;
	private double backBest = Double.POSITIVE_INFINITY;
	private int backStall;
	private long lastClink = -100;
	private final Set<UUID> legHits = new HashSet<>();
	private final Map<UUID, Integer> hitCounts = new HashMap<>();
	private boolean enchantsRead;
	private int sharpness, smite, bane, knockback, fireAspect;

	public ThrowingDiscEntity(EntityType<? extends ThrowingDiscEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_ITEM, ItemStack.EMPTY);
	}

	/** Used by the item renderer. */
	@Override
	public ItemStack getItem() {
		return this.entityData.get(DATA_ITEM);
	}

	private DiscTier tier() {
		return getItem().getItem() instanceof ThrowingDiscItem disc ? disc.tier : DiscTier.IRON;
	}

	// ---- throwing -----------------------------------------------------------------------------
	/**
	 * Throw the disc held in {@code hand}. Returns false if the throw is refused (cooldown, spectator,
	 * last point of durability).
	 */
	public static boolean tryThrow(ServerLevel level, ServerPlayer player, InteractionHand hand, ItemStack stack, DiscTier tier) {
		long now = level.getGameTime();
		if (LOCKED_UNTIL.getOrDefault(player.getUUID(), -1L) > now) {
			return false;
		}
		if (player.isSpectator()) {
			return false;
		}
		boolean creative = player.isCreative();

		ItemStack thrown = stack.copyWithCount(1);
		if (!creative && thrown.isDamageableItem()) {
			// Like the trident: a disc on its very last point of durability won't leave your hand.
			if (thrown.getMaxDamage() - thrown.getDamageValue() <= 1) {
				return false;
			}
			int cost = EnchantmentHelper.processDurabilityChange(level, thrown, 1); // honours Unbreaking
			thrown.setDamageValue(Math.min(thrown.getMaxDamage() - 1, thrown.getDamageValue() + cost));
		}

		Vec3 look = player.getLookAngle().normalize();
		Vec3 right = norm(new Vec3(-look.z, 0, look.x), new Vec3(1, 0, 0));
		boolean rightHanded = (hand == InteractionHand.MAIN_HAND) == (player.getMainArm() == HumanoidArm.RIGHT);

		ThrowingDiscEntity disc = new ThrowingDiscEntity(ModEntities.THROWING_DISC, level);
		Vec3 center = player.getEyePosition().add(0, -0.2, 0);
		// Release point a little in front, on the throwing hand's side - unless a wall is right there.
		Vec3 release = look.scale(0.4).add(right.scale(rightHanded ? 0.25 : -0.25));
		disc.setPos(center);
		Contact wall = disc.sweep(center, norm(release, look), release.length());
		if (wall == null) {
			center = center.add(release);
		}
		disc.setPos(center);

		disc.ownerId = player.getUUID();
		disc.slot = hand == InteractionHand.MAIN_HAND ? player.getInventory().getSelectedSlot() : OFFHAND_SLOT;
		disc.creative = creative;
		disc.dir = look;
		disc.entityData.set(DATA_ITEM, thrown);
		disc.readEnchants(level);
		level.addFreshEntity(disc);

		if (!creative) {
			stack.shrink(1); // same stack object, so the hand ends up empty
		}
		LOCKED_UNTIL.put(player.getUUID(), now + THROW_LOCKOUT);
		disc.play(SoundEvents.TRIDENT_THROW, center, tier.pitch, 0.8F);
		return true;
	}

	private void readEnchants(ServerLevel level) {
		ItemStack item = getItem();
		sharpness = enchantLevel(level, item, Enchantments.SHARPNESS);
		smite = enchantLevel(level, item, Enchantments.SMITE);
		bane = enchantLevel(level, item, Enchantments.BANE_OF_ARTHROPODS);
		knockback = enchantLevel(level, item, Enchantments.KNOCKBACK);
		fireAspect = enchantLevel(level, item, Enchantments.FIRE_ASPECT);
		enchantsRead = true;
	}

	private static int enchantLevel(ServerLevel level, ItemStack stack, ResourceKey<Enchantment> key) {
		Holder<Enchantment> holder = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
		return EnchantmentHelper.getItemEnchantmentLevel(holder, stack);
	}

	// ---- vector helpers -----------------------------------------------------------------------
	private static Vec3 norm(Vec3 v, Vec3 fallback) {
		double l = v.length();
		return l > 1e-9 ? v.scale(1 / l) : fallback;
	}

	private static Vec3 reflect(Vec3 d, Vec3 n) {
		return d.subtract(n.scale(2 * d.dot(n)));
	}

	/**
	 * Shortest distance between segments p1-q1 and p2-q2 (Ericson, Real-Time Collision Detection 5.1.9).
	 * Used as a capsule test: disc path vs mob body.
	 */
	private static double segSegDist(Vec3 p1, Vec3 q1, Vec3 p2, Vec3 q2) {
		Vec3 d1 = q1.subtract(p1);
		Vec3 d2 = q2.subtract(p2);
		Vec3 r = p1.subtract(p2);
		double a = d1.dot(d1);
		double e = d2.dot(d2);
		double f = d2.dot(r);
		final double eps = 1e-9;
		double s = 0;
		double t = 0;
		if (a <= eps && e <= eps) {
			return r.length();
		}
		if (a <= eps) {
			t = Mth.clamp(f / e, 0.0, 1.0);
		} else {
			double c = d1.dot(r);
			if (e <= eps) {
				s = Mth.clamp(-c / a, 0.0, 1.0);
			} else {
				double b = d1.dot(d2);
				double denom = a * e - b * b;
				s = denom != 0 ? Mth.clamp((b * f - c * e) / denom, 0.0, 1.0) : 0;
				t = (b * s + f) / e;
				if (t < 0) {
					t = 0;
					s = Mth.clamp(-c / a, 0.0, 1.0);
				} else if (t > 1) {
					t = 1;
					s = Mth.clamp((b - c) / a, 0.0, 1.0);
				}
			}
		}
		return p1.add(d1.scale(s)).distanceTo(p2.add(d2.scale(t)));
	}

	// ---- block classification -------------------------------------------------------------------
	private enum Kind { HARD, SOFT, SLIME, POT, BELL }

	private static Kind blockKind(BlockState state) {
		if (state.is(Blocks.SLIME_BLOCK)) return Kind.SLIME;
		if (state.is(Blocks.DECORATED_POT)) return Kind.POT;
		if (state.is(Blocks.BELL)) return Kind.BELL;
		if (state.is(BlockTags.WOOL) || state.is(BlockTags.LEAVES) || state.is(BlockTags.BEDS)
				|| state.is(Blocks.HAY_BLOCK) || state.is(Blocks.SPONGE) || state.is(Blocks.WET_SPONGE)
				|| state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.PALE_MOSS_BLOCK)
				|| state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW)
				|| state.is(Blocks.HONEY_BLOCK) || state.is(Blocks.MUSHROOM_STEM)
				|| state.is(Blocks.BROWN_MUSHROOM_BLOCK) || state.is(Blocks.RED_MUSHROOM_BLOCK)
				|| state.is(Blocks.AZALEA) || state.is(Blocks.FLOWERING_AZALEA) || state.is(Blocks.DRIED_KELP_BLOCK)) {
			return Kind.SOFT;
		}
		return Kind.HARD;
	}

	// ---- collision ----------------------------------------------------------------------------------
	private record Hit(Vec3 point, Vec3 normal, BlockPos pos, BlockState state) {}

	/** travel = how far the disc may move before touching; blocked = the way is into an unloaded chunk. */
	private record Contact(double travel, Vec3 normal, BlockState state, Vec3 point, boolean blocked) {}

	private record Probe(Vec3 origin, double lead, boolean rim) {}

	private static final Hit BLOCKED_HIT = new Hit(null, null, null, null);
	private static final Contact BLOCKED = new Contact(0, null, null, null, true);

	private Hit castRay(Vec3 from, Vec3 dir, double maxDistance) {
		Vec3 to = from.add(dir.scale(maxDistance));
		if (!level().hasChunkAt(BlockPos.containing(to))) {
			return BLOCKED_HIT; // unloaded chunk: treat like a wall
		}
		BlockHitResult result = level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
		if (result.getType() == HitResult.Type.MISS) {
			return null;
		}
		Direction face = result.getDirection();
		Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
		return new Hit(result.getLocation(), normal, result.getBlockPos(), level().getBlockState(result.getBlockPos()));
	}

	/**
	 * Probe rays for the disc's shape: a flat circle (radius DISC_RADIUS, half thickness DISC_HALF_THICK).
	 * Each rim point on the leading edge gets a ray that triggers when that point itself reaches a surface,
	 * plus a centre ray leading by the disc's extent as a backstop. Moving steeply up or down, the whole
	 * rim and the flat face lead instead.
	 */
	private static java.util.List<Probe> probes(Vec3 c, Vec3 dir) {
		Vec3 horiz = new Vec3(dir.x, 0, dir.z);
		double h = horiz.length();
		Vec3 fwd = h > 1e-3 ? horiz.scale(1 / h) : new Vec3(0, 0, 1);
		Vec3 across = new Vec3(-fwd.z, 0, fwd.x);
		Vec3 face = new Vec3(0, Math.abs(dir.y) > 0.05 ? Math.signum(dir.y) * DISC_HALF_THICK : 0, 0);
		java.util.List<Probe> list = new java.util.ArrayList<>();
		list.add(new Probe(c, DISC_RADIUS * h + DISC_HALF_THICK * Math.abs(dir.y), false));
		boolean steep = Math.abs(dir.y) > 0.6;
		for (double r : steep ? RIM_ALL : RIM_FRONT) {
			Vec3 rim = fwd.scale(DISC_RADIUS * Math.cos(r)).add(across.scale(DISC_RADIUS * Math.sin(r)));
			list.add(new Probe(steep ? c.add(rim).add(face) : c.add(rim), 0, true));
		}
		if (face.y != 0) {
			list.add(new Probe(c.add(face), 0, true));
			if (steep) { // inner ring of the flat face
				for (int i = 0; i < 6; i++) {
					double r = i * Math.PI / 3;
					Vec3 inner = fwd.scale(0.18 * Math.cos(r)).add(across.scale(0.18 * Math.sin(r)));
					list.add(new Probe(c.add(inner).add(face), 0, true));
				}
			}
		}
		return list;
	}

	/** Sweep the whole disc from c along unit dir for up to {@code distance} blocks. Null = clear. */
	private Contact sweep(Vec3 c, Vec3 dir, double distance) {
		Contact best = null;
		for (Probe p : probes(c, dir)) {
			Hit hit = castRay(p.origin(), dir, distance + p.lead() + SKIN);
			if (hit == null) continue;
			if (hit == BLOCKED_HIT) {
				if (p.rim()) continue;
				return BLOCKED;
			}
			if (dir.dot(hit.normal()) > -0.01) continue; // only faces it is actually moving into
			Vec3 point = hit.point();
			double along = point.subtract(p.origin()).dot(dir);
			if (p.rim() && along < 0.02) {
				// A rim point already against a block (disc resting on the wall it just bounced off)
				// reports that wall at distance 0 - ignore it if the rim point is on the far side of a
				// surface from the centre.
				Vec3 off = p.origin().subtract(c);
				if (castRay(c, norm(off, new Vec3(0, 0, 1)), off.length() * 0.98) != null) continue;
			}
			double travel = Mth.clamp(along - p.lead() - SKIN, 0.0, distance);
			if (best == null || travel < best.travel()) {
				best = new Contact(travel, hit.normal(), hit.state(), point, false);
			}
		}
		return best;
	}

	/** Sweep, smashing any decorated pots on the way (they don't stop the disc). */
	private Contact sweepThroughPots(ServerLevel level, Vec3 c, Vec3 dir, double distance, Player owner) {
		for (int i = 0; i < 4; i++) {
			Contact contact = sweep(c, dir, distance);
			if (contact == null || contact.blocked() || blockKind(contact.state()) != Kind.POT) {
				return contact;
			}
			level.destroyBlock(BlockPos.containing(contact.point().add(dir.scale(0.05))), true, owner);
		}
		return sweep(c, dir, distance);
	}

	// ---- helpers -------------------------------------------------------------------------------------
	private void play(SoundEvent sound, Vec3 at, float pitch, float volume) {
		float jitter = 0.92F + this.random.nextFloat() * 0.16F;
		level().playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch * jitter);
	}

	private static Vec3 catchPoint(Player owner) {
		return owner.position().add(0, 0.95, 0);
	}

	private Player getOwner(ServerLevel level, Vec3 at) {
		if (ownerId == null) return null;
		Player p = level.getPlayerByUUID(ownerId);
		if (p == null || !p.isAlive() || p.isSpectator()) return null;
		if (p.position().distanceTo(at) > MAX_OWNER_DISTANCE) return null;
		return p;
	}

	private void startReturn(Vec3 at, boolean ownerPresent) {
		if (returning) return;
		returning = true;
		backSpeed = RETURN_START_SPEED;
		// Keeps its momentum, slows, then comes straight back - no sideways swing.
		vel = dir.scale(tier().speed * 0.8);
		legHits.clear();
		if (ownerPresent) play(SoundEvents.TRIDENT_RETURN, at, tier().pitch, 0.6F);
	}

	private void giveBack(Player owner) {
		ItemStack item = getItem().copy();
		Inventory inv = owner.getInventory();
		if (slot >= 0 && slot < inv.getContainerSize() && inv.getItem(slot).isEmpty()) {
			inv.setItem(slot, item);
			return;
		}
		inv.add(item);
		if (!item.isEmpty()) {
			owner.drop(item, false);
		}
	}

	private void catchDisc(Player owner) {
		if (!creative) {
			giveBack(owner);
		}
		play(SoundEvents.ITEM_PICKUP, owner.position(), 1.0F, 0.8F);
		LOCKED_UNTIL.put(owner.getUUID(), level().getGameTime() + CATCH_LOCKOUT);
		discard();
	}

	private void dropDisc() {
		Vec3 at = position();
		if (!creative) {
			ItemEntity drop = new ItemEntity(level(), at.x, at.y, at.z, getItem().copy());
			level().addFreshEntity(drop);
			play(SoundEvents.TRIDENT_HIT_GROUND, at, tier().pitch, 0.6F);
		}
		discard();
	}

	private boolean canHit(LivingEntity target, Player owner) {
		if (!target.isAlive()) return false;
		if (target instanceof Player other) {
			if (other.isCreative() || other.isSpectator()) return false;
			if (owner == null || !owner.canHarmPlayer(other)) return false; // PvP rule + team friendly fire
		}
		// Leave tamed animals alone - your dog chasing your target shouldn't eat a disc.
		if (target instanceof TamableAnimal pet && pet.isTame()) return false;
		return owner == null || owner.getVehicle() != target;
	}

	private void strike(ServerLevel level, LivingEntity target, Player owner, Vec3 dirN) {
		double damage = tier().damage + 1.25 * sharpness;
		if (smite > 0 && target.is(EntityTypeTags.UNDEAD)) damage += 2.5 * smite;
		if (bane > 0 && target.is(EntityTypeTags.ARTHROPOD)) damage += 2.5 * bane;
		DamageSource source = level.damageSources().thrown(this, owner);
		if (!target.hurtServer(level, source, (float) damage)) return;
		double kb = 0.35 + 0.5 * knockback;
		target.knockback(kb, -dirN.x, -dirN.z); // LivingEntity#knockback takes the direction to push *away from*
		if (fireAspect > 0) {
			target.igniteForSeconds(4.0F * fireAspect);
		}
		play(SoundEvents.TRIDENT_HIT, target.position(), tier().pitch, 0.8F);
	}

	/** Damage everything the disc's path from a to b passes through. */
	private void hitAlong(ServerLevel level, Vec3 a, Vec3 b, Player owner) {
		Vec3 seg = b.subtract(a);
		double segLen = seg.length();
		Vec3 dirN = segLen > 1e-6 ? seg.scale(1 / segLen) : dir;
		List<Entity> targets = level.getEntities(this, new AABB(a, b).inflate(3.0), e -> e instanceof LivingEntity);
		for (Entity t : targets) {
			if (!(t instanceof LivingEntity living) || t.isRemoved()) continue;
			UUID id = t.getUUID();
			if (id.equals(ownerId)) continue;
			if (legHits.contains(id) || hitCounts.getOrDefault(id, 0) >= MAX_HITS_PER_TARGET) continue;
			if (!canHit(living, owner)) continue;
			Vec3 feet = t.position();
			double h = Math.max(0.3, t.getEyeHeight());
			Vec3 top = feet.add(0, h * 1.15 + 0.1, 0);
			double reach = DISC_RADIUS + Mth.clamp(h * 0.3, 0.25, 1.4);
			if (segSegDist(a, b, feet, top) > reach) continue;
			legHits.add(id);
			hitCounts.merge(id, 1, Integer::sum);
			strike(level, living, owner, dirN);
		}
	}

	// ---- flight ---------------------------------------------------------------------------------------
	@Override
	public void tick() {
		super.tick();
		if (!(level() instanceof ServerLevel level)) {
			return;
		}
		if (getItem().isEmpty()) { // nothing to throw (corrupt save)
			discard();
			return;
		}
		if (!enchantsRead) readEnchants(level);
		if (++age > MAX_AGE) {
			dropDisc();
			return;
		}

		Vec3 center = position();
		Player owner = getOwner(level, center);

		// Catchable any time after leaving the hand, so a ricochet straight back is a catch too.
		if (owner != null && age >= MIN_CATCH_AGE && center.distanceTo(catchPoint(owner)) <= CATCH_RADIUS) {
			catchDisc(owner);
			return;
		}

		Vec3 end;
		DiscTier tier = tier();

		if (!returning) {
			double speed = tier.speed * (isInWater() ? 0.6 : 1.0);
			Contact contact = sweepThroughPots(level, center, dir, speed, owner);
			if (contact != null && contact.blocked()) {
				end = center;
				startReturn(center, owner != null);
			} else if (contact == null) {
				end = center.add(dir.scale(speed));
			} else {
				// Stop on the surface this tick - the bounce is seen touching the block.
				end = center.add(dir.scale(contact.travel())).add(contact.normal().scale(SKIN));
				Kind kind = blockKind(contact.state());
				legHits.clear();
				dir = norm(reflect(dir, contact.normal()), dir);
				if (kind == Kind.SOFT) {
					play(SoundEvents.WOOL_HIT, contact.point(), 1.0F, 0.9F);
					startReturn(end, owner != null);
				} else {
					level.sendParticles(ParticleTypes.CRIT, contact.point().x, contact.point().y, contact.point().z, 4, 0.05, 0.05, 0.05, 0.1);
					if (kind == Kind.SLIME) {
						outLeft += SLIME_BONUS_TICKS;
						play(SoundEvents.SLIME_BLOCK_HIT, contact.point(), 1.0F, 1.0F);
					} else {
						bounces++;
						outLeft += BOUNCE_BONUS_TICKS;
						play(SoundEvents.TRIDENT_HIT_GROUND, contact.point(), tier.pitch, 0.9F);
						if (kind == Kind.BELL) play(SoundEvents.BELL_BLOCK, contact.point(), 1.0F, 1.0F);
						if (bounces > MAX_BOUNCES) startReturn(end, owner != null);
					}
				}
			}
			outLeft--;
			if (!returning && outLeft <= 0) startReturn(end, owner != null);
		} else {
			if (owner == null) { // thrower gone (left, died, changed dimension)
				dropDisc();
				return;
			}
			Vec3 target = catchPoint(owner);
			Vec3 toTarget = target.subtract(center);
			double d = toTarget.length();
			// Close enough and nothing in between: caught.
			if (d <= backSpeed + 0.6 && castRay(center, norm(toTarget, dir), d) == null) {
				catchDisc(owner);
				return;
			}

			// Progress check: if it hasn't got any closer for a while, it's stuck behind something.
			if (d < backBest - 0.2) {
				backBest = d;
				backStall = 0;
			} else if (++backStall > RETURN_STALL_TICKS) {
				dropDisc();
				return;
			}

			// Boomerang return: momentum decays while a pull toward the thrower builds, so it slows,
			// stops and flies straight back (curving only if you've moved).
			double maxSpeed = RETURN_MAX_SPEED * (tier.speed / 1.3);
			vel = vel.scale(RETURN_DRAG).add(norm(toTarget, dir).scale(RETURN_PULL));
			double speed = vel.length();
			if (speed > maxSpeed) vel = vel.scale(maxSpeed / speed);
			backSpeed = Math.min(speed, maxSpeed);
			dir = norm(vel, dir);
			double step = backSpeed;

			Contact contact = sweepThroughPots(level, center, dir, step, owner);
			if (contact != null && contact.blocked()) {
				dropDisc();
				return;
			}
			if (contact == null) {
				end = center.add(dir.scale(step));
			} else {
				end = center.add(dir.scale(contact.travel())).add(contact.normal().scale(SKIN));
				double into = dir.dot(contact.normal());
				if (into < -0.55 && backSpeed > 0.4 && level.getGameTime() - lastClink > 4) {
					lastClink = level.getGameTime();
					play(blockKind(contact.state()) == Kind.SOFT ? SoundEvents.WOOL_HIT : SoundEvents.TRIDENT_HIT_GROUND,
							contact.point(), tier.pitch, 0.5F);
				}
				// Slide along the surface toward home (e.g. skims over a ledge instead of tunnelling through it).
				double vin = vel.dot(contact.normal());
				vel = vel.subtract(contact.normal().scale(vin)).scale(0.85);
				dir = norm(vel, dir);
			}
		}

		hitAlong(level, center, end, owner);
		if (isRemoved()) return;
		setPos(end);
	}

	// ---- misc entity plumbing ----------------------------------------------------------------------------
	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false; // can't be shot, punched or burned
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.store("Item", ItemStack.OPTIONAL_CODEC, getItem());
		if (ownerId != null) output.putString("Owner", ownerId.toString());
		output.putInt("Slot", slot);
		output.putBoolean("Creative", creative);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		this.entityData.set(DATA_ITEM, input.read("Item", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
		String owner = input.getStringOr("Owner", "");
		try {
			ownerId = owner.isEmpty() ? null : UUID.fromString(owner);
		} catch (IllegalArgumentException e) {
			ownerId = null;
		}
		slot = input.getIntOr("Slot", -1);
		creative = input.getBooleanOr("Creative", false);
		// A disc that was mid-flight when the world was saved just heads home.
		returning = true;
		age = MIN_CATCH_AGE;
		vel = Vec3.ZERO;
	}
}
