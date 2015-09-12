package fr.neatmonster.nocheatplus.checks.moving;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import fr.neatmonster.nocheatplus.NCPAPIProvider;
import fr.neatmonster.nocheatplus.actions.ParameterName;
import fr.neatmonster.nocheatplus.checks.Check;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.compat.BridgeEnchant;
import fr.neatmonster.nocheatplus.logging.Streams;
import fr.neatmonster.nocheatplus.permissions.Permissions;
import fr.neatmonster.nocheatplus.utilities.ActionAccumulator;
import fr.neatmonster.nocheatplus.utilities.BlockCache;
import fr.neatmonster.nocheatplus.utilities.BlockProperties;
import fr.neatmonster.nocheatplus.utilities.PlayerLocation;
import fr.neatmonster.nocheatplus.utilities.StringUtil;
import fr.neatmonster.nocheatplus.utilities.TrigUtil;
import fr.neatmonster.nocheatplus.utilities.build.BuildParameters;

/**
 * The counterpart to the CreativeFly check. People that are not allowed to fly get checked by this. It will try to
 * identify when they are jumping, check if they aren't jumping too high or far, check if they aren't moving too fast on
 * normal ground, while sprinting, sneaking, swimming, etc.
 */
public class SurvivalFly extends Check {

    // Tags
    private static final String DOUBLE_BUNNY = "doublebunny";

    // Horizontal speeds/modifiers.
    public static final double walkSpeed            = 0.221D;

    public static final double modSneak             = 0.13D / walkSpeed;
    //    public static final double modSprint            = 0.29D / walkSpeed; // TODO: without bunny  0.29 / practical is 0.35

    public static final double modBlock             = 0.16D / walkSpeed;
    public static final double modSwim              = 0.115D / walkSpeed;
    public static final double[] modDepthStrider    = new double[] {
        1.0,
        0.1645 / modSwim / walkSpeed,
        0.1995 / modSwim / walkSpeed,
        1.0 / modSwim, // Results in walkspeed.
    };

    public static final double modWeb               = 0.105D / walkSpeed; // TODO: walkingSpeed * 0.15D; <- does not work

    public static final double modIce                 = 2.5D; // 

    /** Faster moving down stream (water mainly). */
    public static final double modDownStream	= 0.19 / (walkSpeed * modSwim);

    /** Maximal horizontal buffer. It can be higher, but normal resetting should keep this limit. */
    public static final double hBufMax			= 1.0;

    // Vertical speeds/modifiers. 
    public static final double climbSpeed		= walkSpeed * 1.3; // TODO: Check if the factor is needed!  

    // Other.
    /** Bunny-hop delay. */
    private static final int   bunnyHopMax = 10;
    /** Divisor vs. last hDist for minimum slow down. */
    private static final double bunnyDivFriction = 160.0; // Rather in-air, blocks would differ by friction.

    // Gravity.
    public static final double gravity = 0.0774; // TODO: Model / check.

    // Friction factor by medium.
    public static final double FRICTION_MEDIUM_AIR = 0.98; // TODO: Check
    public static final double FRICTION_MEDIUM_LIQUID = 0.89; // Rough estimate for horizontal move sprint-jump into water.

    // TODO: Friction by block to walk on (horizontal only, possibly to be in BlockProperties rather).

    /** To join some tags with moving check violations. */
    private final ArrayList<String> tags = new ArrayList<String>(15);


    private final Set<String> reallySneaking = new HashSet<String>(30);

    /** For temporary use: LocUtil.clone before passing deeply, call setWorld(null) after use. */
    private final Location useLoc = new Location(null, 0, 0, 0);

    /**
     * Instantiates a new survival fly check.
     */
    public SurvivalFly() {
        super(CheckType.MOVING_SURVIVALFLY);
    }

    /**
     * Checks a player.
     * 
     * @param player
     *            the player
     * @param from
     *            the from
     * @param to
     *            the to
     * @param isSamePos 
     * @return the location
     */
    public Location check(final Player player, final PlayerLocation from, final Location loc, final PlayerLocation to, final boolean isSamePos, final MovingData data, final MovingConfig cc, final long now) {
        tags.clear();

        // Calculate some distances.
        final double xDistance, yDistance, zDistance, hDistance;
        final boolean hasHdist;
        if (isSamePos) {
            // TODO: Could run a completely different check here (roughly none :p).
            xDistance = yDistance = zDistance = hDistance = 0.0;
            hasHdist = false;
        } else {
            xDistance = to.getX() - from.getX();
            yDistance = to.getY() - from.getY();
            zDistance = to.getZ() - from.getZ();
            if (xDistance == 0.0 && zDistance == 0.0) {
                hDistance = 0.0;
                hasHdist = false;
            } else {
                hDistance = Math.sqrt(xDistance * xDistance + zDistance * zDistance);
                hasHdist = true;
            }
        }

        // Ensure we have a set-back location set, plus allow moving from upwards with respawn/login.
        if (!data.hasSetBack()) {
            data.setSetBack(from);
        }
        else if (data.joinOrRespawn && from.getY() > data.getSetBackY() && 
                TrigUtil.isSamePos(from.getX(), from.getZ(), data.getSetBackX(), data.getSetBackZ()) &&
                (from.isOnGround() || from.isResetCond())) {
            // TODO: Move most to a method?
            // TODO: Is a margin needed for from.isOnGround()? [bukkitapionly]
            if (cc.debug) {
                NCPAPIProvider.getNoCheatPlusAPI().getLogManager().debug(Streams.TRACE_FILE, player.getName() + " SurvivalFly\nAdjust set-back after join/respawn: " + from.getLocation());
            }
            data.setSetBack(from);
            data.resetPositions(from);
        }

        // Set some flags.
        final boolean fromOnGround = from.isOnGround();
        final boolean toOnGround = to.isOnGround();
        final boolean resetTo = toOnGround || to.isResetCond();

        // Determine if the player is actually sprinting.
        final boolean sprinting;
        if (data.lostSprintCount > 0) {
            // Sprint got toggled off, though the client is still (legitimately) moving at sprinting speed.
            // NOTE: This could extend the "sprinting grace" period, theoretically, until on ground.
            if (resetTo && (fromOnGround || from.isResetCond()) || hDistance <= walkSpeed) {
                // Invalidate.
                data.lostSprintCount = 0;
                tags.add("invalidate_lostsprint");
                if (now <= data.timeSprinting + cc.sprintingGrace) {
                    sprinting = true;
                } else {
                    sprinting = false;
                }
            }
            else {
                tags.add("lostsprint");
                sprinting = true;
                if (data.lostSprintCount < 3 && to.isOnGround() || to.isResetCond()) {
                    data.lostSprintCount = 0;
                }
                else {
                    data.lostSprintCount --;
                }
            }
        }
        else if (now <= data.timeSprinting + cc.sprintingGrace) {
            // Within grace period for hunger level being too low for sprinting on server side (latency).
            if (now != data.timeSprinting) {
                tags.add("sprintgrace");
            }
            sprinting = true;
        }
        else {
            sprinting = false;
        }

        // Use the player-specific walk speed.
        // TODO: Might get from listener.
        // TODO: Use in lostground?
        final double walkSpeed = SurvivalFly.walkSpeed * ((double) data.walkSpeed / 0.2);

        setNextFriction(from, to, data, cc);

        /////////////////////////////////
        // Mixed checks (lost ground).
        /////////////////////////////////


        final boolean resetFrom;
        if (fromOnGround || from.isResetCond()) {
            resetFrom = true;
        }
        // TODO: Extra workarounds for toOnGround (step-up is a case with to on ground)?
        else if (isSamePos) {
            // TODO: This isn't correct, needs redesign.
            if (data.sfLastHDist != Double.MAX_VALUE && data.sfLastHDist > 0.0 && data.sfLastYDist < -0.3) {
                // Note that to is not on ground either.
                resetFrom = lostGroundStill(player, from, loc, to, hDistance, yDistance, sprinting, data, cc);
            } else {
                resetFrom = false;
            }
        }
        else {
            // "Lost ground" workaround.
            // TODO: More refined conditions possible ?
            // TODO: Consider if (!resetTo) ?
            // Check lost-ground workarounds.
            resetFrom = lostGround(player, from, loc, to, hDistance, yDistance, sprinting, data, cc);
            // Note: if not setting resetFrom, other places have to check assumeGround...
        }

        //////////////////////
        // Horizontal move.
        //////////////////////

        // TODO: Account for lift-off medium / if in air [i.e. account for medium + friction]?

        // Alter some data / flags.
        data.bunnyhopDelay--; // TODO: Design to do the changing at the bottom? [if change: check limits in bunnyHop(...)]

        // Set flag for swimming with the flowing direction of liquid.
        final boolean downStream = hDistance > walkSpeed * modSwim && from.isInLiquid() && from.isDownStream(xDistance, zDistance);

        // Handle ice.
        // TODO: Re-model ice stuff and other (e.g. general thing: ground-modifier + reset conditions).
        if (from.isOnIce() || to.isOnIce()) {
            data.sfOnIce = 20;
        }
        else if (data.sfOnIce > 0) {
            // TODO: Here some friction might apply, could become a general thing with bunny and other.
            // TODO: Other reset conditions.
            data.sfOnIce--;
        }

        double hAllowedDistance = 0.0, hDistanceAboveLimit = 0.0, hFreedom = 0.0;
        if (hasHdist) {
            // Check allowed vs. taken horizontal distance.
            // Get the allowed distance.
            hAllowedDistance = getAllowedhDist(player, from, to, sprinting, downStream, hDistance, walkSpeed, data, cc, false);

            // Judge if horizontal speed is above limit.
            hDistanceAboveLimit = hDistance - hAllowedDistance;

            // Velocity, buffers and after failure checks.
            if (hDistanceAboveLimit > 0) {
                // TODO: Move more of the workarounds (buffer, bunny, ...) into this method.
                final double[] res = hDistAfterFailure(player, from, to, walkSpeed, hAllowedDistance, hDistance, hDistanceAboveLimit, yDistance, sprinting, downStream, data, cc, false);
                hAllowedDistance = res[0];
                hDistanceAboveLimit = res[1];
                hFreedom = res[2];
            }
            else {
                data.clearActiveHorVel();
                hFreedom = 0.0;
                if (resetFrom && data.bunnyhopDelay <= 6) {
                    data.bunnyhopDelay = 0;
                }
            }

            // Prevent players from walking on a liquid in a too simple way.
            // TODO: Find something more effective against more smart methods (limitjump helps already).
            // TODO: yDistance == 0D <- should there not be a tolerance +- or 0...x ?
            // TODO: Complete re-modeling.
            if (hDistanceAboveLimit <= 0D && hDistance > 0.1D && yDistance == 0D && data.sfLastYDist == 0D && !toOnGround && !fromOnGround && BlockProperties.isLiquid(to.getTypeId())) {
                // TODO: Relative hdistance.
                // TODO: Might check actual bounds (collidesBlock). Might implement + use BlockProperties.getCorrectedBounds or getSomeHeight.
                hDistanceAboveLimit = Math.max(hDistanceAboveLimit, hDistance);
                tags.add("waterwalk");
            }

            // Prevent players from sprinting if they're moving backwards (allow buffers to cover up !?).
            if (sprinting && data.lostSprintCount == 0 && !cc.assumeSprint && hDistance > walkSpeed && !data.hasActiveHorVel()) {
                // (Ignore some cases, in order to prevent false positives.)
                // TODO: speed effects ?
                if (TrigUtil.isMovingBackwards(xDistance, zDistance, from.getYaw()) && !player.hasPermission(Permissions.MOVING_SURVIVALFLY_SPRINTING)) {
                    // (Might have to account for speeding permissions.)
                    // TODO: hDistance is too harsh?
                    hDistanceAboveLimit = Math.max(hDistanceAboveLimit, hDistance);
                    tags.add("sprintback"); // Might add it anyway.
                }
            }
        } else {
            /*
             * TODO: Consider to log and/or remember when this was last time
             * cleared [add time distance to tags/log on violations].
             */
            data.clearActiveHorVel();
        }


        //////////////////////////
        // Vertical move.
        //////////////////////////

        // Account for "dirty"-flag (allow less for normal jumping).
        final boolean sfDirty;
        if (data.isVelocityJumpPhase()) {
            if (!resetFrom && !resetTo || data.resetVelocityJumpPhase()) {
                // TODO: If resetTo && !resetfrom -> too early reset ?
                sfDirty = true;
                tags.add("dirty");
            }
            else {
                sfDirty = false;
            }
        } else {
            sfDirty = false;
        }

        // Calculate the vertical speed limit based on the current jump phase.
        double vAllowedDistance = 0, vDistanceAboveLimit = 0;
        // Distinguish certain media.
        if (from.isInWeb()) {
            // TODO: Further confine conditions.
            final double[] res = vDistWeb(player, from, to, toOnGround, hDistanceAboveLimit, yDistance, now,data,cc);
            vAllowedDistance = res[0];
            vDistanceAboveLimit = res[1];
            if (res[0] == Double.MIN_VALUE && res[1] == Double.MIN_VALUE) {
                // Silent set-back.
                if (data.debug) {
                    tags.add("silentsbcobweb");
                    outputDebug(player, to, data, cc, hDistance, hAllowedDistance, hFreedom, yDistance, vAllowedDistance, fromOnGround, resetFrom, toOnGround, resetTo);
                }
                return data.getSetBack(to);
            }
        }
        else if (!sfDirty && from.isOnClimbable()) {
            // Ladder types.
            vDistanceAboveLimit = vDistClimbable(player, from, fromOnGround, toOnGround, yDistance, data);
        }
        else if (!sfDirty && from.isInLiquid() && (Math.abs(yDistance) > 0.2 || to.isInLiquid())) {
            // Swimming...
            final double[] res = vDistLiquid(from, to, toOnGround, yDistance, data);
            vAllowedDistance = res[0];
            vDistanceAboveLimit = res[1];
        }
        else {
            // Check y-distance for normal jumping, like in air.
            // TODO: Can it be easily transformed to a more accurate max. absolute height?
            vAllowedDistance = 1.35D + data.getVerticalFreedom();
            int maxJumpPhase;
            if (data.mediumLiftOff == MediumLiftOff.LIMIT_JUMP) {
                // TODO: In normal water this is 0. Could set higher for special cases only (needs efficient data + flags collection?).
                maxJumpPhase = 3;
                data.sfNoLowJump = true;
                if (data.sfJumpPhase > 0) {
                    tags.add("limitjump");
                }
            }
            else if (data.jumpAmplifier > 0) {
                vAllowedDistance += 0.6 + data.jumpAmplifier - 1.0;
                maxJumpPhase = (int) (9 + (data.jumpAmplifier - 1.0) * 6);
            }
            else {
                maxJumpPhase = 6;
            }
            if (data.sfJumpPhase > maxJumpPhase && data.getVerticalFreedom() <= 0 && !data.isVelocityJumpPhase()) {
                if (yDistance < 0) {
                    // Ignore falling, and let accounting deal with it.
                }
                else if (resetFrom) {
                    // Ignore bunny etc.
                }
                else {
                    // Violation (Too high jumping or step).
                    tags.add("maxphase");
                    vDistanceAboveLimit = Math.max(vDistanceAboveLimit, Math.max(yDistance, 0.15));
                }
            }

            // Y-distance to set back.
            // TODO: This might need max(0, for ydiff)
            final double totalVDistViolation =  to.getY() - data.getSetBackY() - vAllowedDistance;
            if (totalVDistViolation > 0.0) {
                // Skip if the player could step up.
                if (yDistance < 0.0 || yDistance > cc.sfStepHeight || !tags.contains("lostground_couldstep")) {
                    vDistanceAboveLimit = Math.max(vDistanceAboveLimit, totalVDistViolation);
                }
            }

            // Add vdist tag.
            if (vDistanceAboveLimit > 0) {
                // Tag only for speed / travel-distance checking.
                tags.add("vdist");
            }

            // More in air checks.
            // TODO: move into the in air checking above !?
            if (!resetFrom && !resetTo) {
                // "On-air" checks (vertical)
                vDistanceAboveLimit = Math.max(vDistanceAboveLimit, inAirChecks(now, from, to, hDistance, yDistance, data, cc));
            }

            // Simple-step blocker.
            // TODO: Complex step blocker: distance to set-back + low jump + accounting info
            if ((resetFrom || data.noFallAssumeGround) && resetTo && vDistanceAboveLimit <= 0D && 
                    yDistance > cc.sfStepHeight && yDistance > MovingUtil.estimateJumpLiftOff(player, data, 0.1)) {
                boolean step = true;
                // Exclude a lost-ground case.
                if (data.noFallAssumeGround && data.sfLastYDist != Double.MAX_VALUE && data.sfLastYDist <= 0.0 && yDistance > 0.0 && 
                        yDistance + Math.abs(data.sfLastYDist) <= 2.0 * (MovingUtil.estimateJumpLiftOff(player, data, 0.1))) {
                    step = false;
                }
                // Check bypass permission last.
                if (step && !player.hasPermission(Permissions.MOVING_SURVIVALFLY_STEP)) {
                    vDistanceAboveLimit = Math.max(vDistanceAboveLimit, Math.abs(from.isOnClimbable() ? yDistance : yDistance - MovingUtil.estimateJumpLiftOff(player, data, 0.1) )); // Could adjust if on ladders etc.
                    tags.add("step");
                }
            }
        }

        // TODO: on ground -> on ground improvements

        // Debug output.
        final int tagsLength;
        if (data.debug) {
            outputDebug(player, to, data, cc, hDistance, hAllowedDistance, hFreedom, yDistance, vAllowedDistance, fromOnGround, resetFrom, toOnGround, resetTo);
            tagsLength = tags.size();
        } else {
            tagsLength = 0; // JIT vs. IDE.
        }

        // Handle violations.
        final double result = (Math.max(hDistanceAboveLimit, 0D) + Math.max(vDistanceAboveLimit, 0D)) * 100D;
        if (result > 0D) {
            final Location vLoc = handleViolation(now, result, player, from, to, data, cc);
            if (vLoc != null) return vLoc;
        }
        else {
            // Slowly reduce the level with each event, if violations have not recently happened.
            if (now - data.sfVLTime > cc.survivalFlyVLFreeze) {
                data.survivalFlyVL *= 0.95D;
            }

            // Finally check horizontal buffer regain.
            if (hDistanceAboveLimit < 0.0  && result <= 0.0 && !isSamePos && data.sfHorizontalBuffer < hBufMax) {
                // TODO: max min other conditions ?
                hBufRegain(hDistance, Math.min(0.2, Math.abs(hDistanceAboveLimit)), data);
            }
        }

        //  Set data for normal move or violation without cancel (cancel would have returned above)

        // Check lift-off medium.
        // TODO: Web before liquid? Climbable?
        // TODO: Web might be NO_JUMP !
        // TODO: isNextToGround(0.15, 0.4) allows a little much (yMargin), but reduces false positives.
        // TODO: nextToGround: Shortcut with block-flags ?
        if (to.isInLiquid()) {
            if (fromOnGround && !toOnGround && data.mediumLiftOff == MediumLiftOff.GROUND && data.sfJumpPhase <= 0  && !from.isInLiquid()) {
                data.mediumLiftOff = MediumLiftOff.GROUND;
            }
            else if (to.isNextToGround(0.15, 0.4)) {
                // Consent with ground.
                data.mediumLiftOff = MediumLiftOff.GROUND;
            } 
            else {
                data.mediumLiftOff = MediumLiftOff.LIMIT_JUMP;
            }
        }
        else if (to.isInWeb()) {
            data.mediumLiftOff = MediumLiftOff.LIMIT_JUMP;
        }
        else if (resetTo) {
            // TODO: This might allow jumping on vines etc., but should do for the moment.
            data.mediumLiftOff = MediumLiftOff.GROUND;
        }
        else if (from.isInLiquid()) {
            if (!resetTo && data.mediumLiftOff == MediumLiftOff.GROUND && data.sfJumpPhase <= 0) {
                data.mediumLiftOff = MediumLiftOff.GROUND;
            }
            else if (to.isNextToGround(0.15, 0.4)) {
                data.mediumLiftOff = MediumLiftOff.GROUND;
            }
            else {
                data.mediumLiftOff = MediumLiftOff.LIMIT_JUMP;
            }
        }
        else if (from.isInWeb()) {
            data.mediumLiftOff = MediumLiftOff.LIMIT_JUMP;
        }
        else if (resetFrom || data.noFallAssumeGround) {
            // TODO: Where exactly to put noFallAssumeGround ?
            data.mediumLiftOff = MediumLiftOff.GROUND;
        }
        else {
            // Keep medium.
            // TODO: Is above stairs ?
        }

        //        // Invalidation of vertical velocity.
        //        // TODO: This invalidation is wrong in case of already jumped higher (can not be repaired?).
        //        if (yDistance <= 0 && data.sfLastYDist > 0 && data.sfLastYDist != Double.MAX_VALUE 
        //                && data.invalidateVerVelGrace(cc.velocityGraceTicks, false)) {
        //            // (Only prevent counting further up, leaves the freedom.)
        //            tags.add("cap_vvel"); // TODO: Test / validate by logs.
        //        }

        // Apply reset conditions.
        if (resetTo) {
            // The player has moved onto ground.
            if (toOnGround) {
                // Reset bunny-hop-delay.
                if (data.bunnyhopDelay > 0 && yDistance > 0.0 && to.getY() > data.getSetBackY() + 0.12 && !from.isResetCond() && !to.isResetCond()) {
                    data.bunnyhopDelay = 0;
                    tags.add("resetbunny");
                }
                // TODO: Experimental: reset vertical velocity.
                if (yDistance < 0.0 && data.invalidateVerVelGrace(cc.velocityGraceTicks, true)) {
                    tags.add("rem_vvel");
                }
            }
            // Reset data.
            data.setSetBack(to);
            data.sfJumpPhase = 0;
            data.clearAccounting();
            data.sfNoLowJump = false;
            if (data.sfLowJump && resetFrom) {
                // Prevent reset if coming from air (purpose of the flag).
                data.sfLowJump = false;
            }
        }
        else if (resetFrom) {
            // The player moved from ground.
            data.setSetBack(from);
            data.sfJumpPhase = 1; // This event is already in air.
            data.clearAccounting();
            data.sfLowJump = false;
            // not resetting nolowjump (?)...
        }
        else {
            data.sfJumpPhase++;
            if (to.getY() < 0.0 && cc.sfSetBackPolicyVoid) {
                data.setSetBack(to);
            }
        }

        // Horizontal velocity invalidation.
        if (hDistance <= (cc.velocityStrictInvalidation ? hAllowedDistance : hAllowedDistance / 2.0)) {
            // TODO: Should there be other side conditions?
            // Invalidate used horizontal velocity.
            //        	NCPAPIProvider.getNoCheatPlusAPI().getLogManager().debug(Streams.TRACE_FILE, "*** INVALIDATE ON SPEED");
            data.clearActiveHorVel();
            //          if (data.horizontalVelocityUsed > cc.velocityGraceTicks) {
            //        	data.horizontalFreedom = 0;
            //        	data.horizontalVelocityCounter = 0;
            //        	data.horizontalVelocityUsed = 0;
            //        }
        }
        // Adjust data.
        data.sfLastHDist = hDistance;
        data.sfLastYDist = yDistance;
        data.toWasReset = resetTo || data.noFallAssumeGround;
        data.fromWasReset = resetFrom || data.noFallAssumeGround;
        data.lastFrictionHorizontal = data.nextFrictionHorizontal;
        data.lastFrictionVertical = data.nextFrictionVertical;
        if (data.debug && tags.size() > tagsLength) {
            logPostViolationTags(player);
        }
        return null;
    }

    /**
     * Set data.nextFriction according to media.
     * @param from
     * @param to
     * @param data
     * @param cc
     */
    private void setNextFriction(final PlayerLocation from, final PlayerLocation to, final MovingData data, final MovingConfig cc) {
        // NOTE: Other methods might still override nextFriction to 1.0 due to burst/lift-off envelope.
        // TODO: Other media / medium transitions / friction by block.
        if (from.isInWeb() || to.isInWeb()) {
            data.nextFrictionHorizontal = data.nextFrictionVertical = 0.0;
        }
        else if (from.isOnClimbable() || to.isOnClimbable()) {
            // TODO: Not sure about horizontal (!).
            data.nextFrictionHorizontal = data.nextFrictionVertical = 0.0;
        }
        else if (from.isInLiquid() && to.isInLiquid()) {
            // TODO: Lava ?
            data.nextFrictionHorizontal = data.nextFrictionVertical = FRICTION_MEDIUM_LIQUID;
        }
        // TODO: consider setting minimum friction last (air), do add ground friction.
        else if (!from.isOnGround() && ! to.isOnGround()) {
            data.nextFrictionHorizontal = data.nextFrictionVertical = FRICTION_MEDIUM_AIR;
        }
        else {
            // TODO: Friction for walking on blocks (!).
        }

    }

    /**
     * Return hAllowedDistance, not exact, check permissions as far as
     * necessary, if flag is set to check them.
     * 
     * @param player
     * @param sprinting
     * @param hDistance
     * @param hAllowedDistance
     * @param data
     * @param cc
     * @param checkPermissions If to check permissions, allowing to speed up a little bit. Only set to true after having failed with it set to false.
     * @return
     */
    private double getAllowedhDist(final Player player, final PlayerLocation from, final PlayerLocation to, final boolean sprinting, final boolean downStream, final double hDistance, final double walkSpeed, final MovingData data, final MovingConfig cc, boolean checkPermissions)
    {
        // TODO: Optimize for double checking?
        double hAllowedDistance = 0D;

        final boolean sfDirty = data.isVelocityJumpPhase();
        double friction = data.lastFrictionHorizontal; // Friction to use with this move.
        if (from.isInWeb()) {
            data.sfOnIce = 0;
            // TODO: if (from.isOnIce()) <- makes it even slower !
            // Does include sprinting by now (would need other accounting methods).
            hAllowedDistance = modWeb * walkSpeed * cc.survivalFlyWalkingSpeed / 100D;
            friction = 0.0; // Ensure friction can't be used to speed.
        }
        else if (from.isInLiquid() && to.isInLiquid()) {
            // Check all liquids (lava might demand even slower speed though).
            // TODO: Test how to go with only checking from (less dolphins).
            // TODO: Sneaking and blocking applies to when in water !
            hAllowedDistance = modSwim * walkSpeed * cc.survivalFlySwimmingSpeed / 100D;
            final int level = BridgeEnchant.getDepthStriderLevel(player);
            if (level > 0) {
                // The hard way.
                hAllowedDistance *= modDepthStrider[level];
                if (sprinting) {
                    hAllowedDistance *= data.multSprinting;
                }
            }
            // (Friction is used as is.)
        }
        else if (!sfDirty && from.isOnGround() && player.isSneaking() && reallySneaking.contains(player.getName()) && (!checkPermissions || !player.hasPermission(Permissions.MOVING_SURVIVALFLY_SNEAKING))) {
            hAllowedDistance = modSneak * walkSpeed * cc.survivalFlySneakingSpeed / 100D;
            friction = 0.0; // Ensure friction can't be used to speed.
        }
        else if (!sfDirty && from.isOnGround() && player.isBlocking() && (!checkPermissions || !player.hasPermission(Permissions.MOVING_SURVIVALFLY_BLOCKING))) {
            hAllowedDistance = modBlock * walkSpeed * cc.survivalFlyBlockingSpeed / 100D;
            friction = 0.0; // Ensure friction can't be used to speed.
        }
        else {
            if (sprinting) {
                hAllowedDistance = walkSpeed * data.multSprinting * cc.survivalFlySprintingSpeed / 100D;
            }
            else {
                hAllowedDistance = walkSpeed * cc.survivalFlyWalkingSpeed / 100D;
            }
            // Count in speed changes (attributes, speed potion).
            // Note: Attributes count in slowness potions, thus leaving out isn't possible.
            final double attrMod = mcAccess.getSpeedAttributeMultiplier(player);
            if (attrMod == Double.MAX_VALUE) {
                // Count in speed potions.
                final double speedAmplifier = mcAccess.getFasterMovementAmplifier(player);
                if (speedAmplifier != Double.NEGATIVE_INFINITY) {
                    hAllowedDistance *= 1.0D + 0.2D * (speedAmplifier + 1);
                }
            } else {
                hAllowedDistance *= attrMod;
            }
            // Ensure friction can't be used to speed.
            // TODO: Model bunny hop as a one time peak + friction. Allow medium based friction.
            friction = 0.0;
        }
        // TODO: Reset friction on too big change of direction?

        // Account for flowing liquids (only if needed).
        // Assume: If in liquids this would be placed right here.
        // TODO: Consider data.mediumLiftOff != ...GROUND
        if (downStream) {
            hAllowedDistance *= modDownStream;
        }

        // If the player is on ice, give them a higher maximum speed.
        if (data.sfOnIce > 0) {
            hAllowedDistance *= modIce;
        }

        // Speeding bypass permission (can be combined with other bypasses).
        if (checkPermissions && player.hasPermission(Permissions.MOVING_SURVIVALFLY_SPEEDING)) {
            hAllowedDistance *= cc.survivalFlySpeedingSpeed / 100D;
        }

        // Friction mechanics (next move).
        if (hDistance <= hAllowedDistance) {
            // Move is within lift-off/burst envelope, allow next time.
            // TODO: This probably is the wrong place (+ bunny, + buffer)?
            data.nextFrictionHorizontal = 1.0;
        }

        // Friction or not (this move).
        if (data.sfLastHDist != Double.MAX_VALUE && friction > 0.0) {
            // Consider friction.
            // TODO: Invalidation mechanics.
            // TODO: Friction model for high speeds?
            return Math.max(hAllowedDistance, data.sfLastHDist * friction);
        } else {
            return hAllowedDistance;
        }
    }

    /**
     * Access method from outside.
     * @param player
     * @return
     */
    public boolean isReallySneaking(final Player player) {
        return reallySneaking.contains(player.getName());
    }

    /**
     * Check if touching the ground was lost (client did not send, or server did not put it through).
     * @param player
     * @param from
     * @param to
     * @param hDistance
     * @param yDistance
     * @param sprinting
     * @param data
     * @param cc
     * @return If touching the ground was lost.
     */
    private boolean lostGround(final Player player, final PlayerLocation from, final Location loc, final PlayerLocation to, final double hDistance, final double yDistance, final boolean sprinting, final MovingData data, final MovingConfig cc) {
        // TODO: Regroup with appropriate conditions (toOnGround first?).
        // TODO: Some workarounds allow step height (0.6 on MC 1.8).
        // TODO: yDistance limit does not seem to be appropriate.
        if (yDistance >= -0.7 && yDistance <= Math.max(cc.sfStepHeight, MovingUtil.estimateJumpLiftOff(player, data, 0.174))) {
            // "Mild" Ascending / descending.
            //Ascending
            if (yDistance >= 0) {
                if (lostGroundAscend(player, from, loc, to, hDistance, yDistance, sprinting, data, cc)) {
                    return true;
                }
            }
            // Descending.
            if (yDistance <= 0) {
                if (lostGroundDescend(player, from, to, hDistance, yDistance, sprinting, data, cc)) {
                    return true;	
                }
            }
            // Stairs.
            // TODO: Stairs should be obsolete by now.
            if (yDistance <= MovingUtil.estimateJumpLiftOff(player, data, 0.1)  && from.isAboveStairs()) {
                applyLostGround(player, from, true, data, "stairs");
                return true;
            }
        }
        else if (yDistance < -0.7) {
            // Clearly descending.
            // TODO: Might want to remove this one.
            if (hDistance <= 0.5) {
                if (lostGroundFastDescend(player, from, to, hDistance, yDistance, sprinting, data, cc)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Extended in-air checks for vertical move: y-direction changes and accounting.
     * 
     * @param now
     * @param yDistance
     * @param data
     * @param cc
     * @return
     */
    private double inAirChecks(final long now, final PlayerLocation from, final PlayerLocation to, final double hDistance, final double yDistance, final MovingData data, final MovingConfig cc) {
        double vDistanceAboveLimit = 0;

        if (data.sfLowJump) {
            tags.add("lowjump");
        }

        // y direction change detection.
        // TODO: Consider using accounting for y-change detection. <- Nope :).
        final boolean yDirChange = data.sfLastYDist != Double.MAX_VALUE && data.sfLastYDist != yDistance && (yDistance <= 0 && data.sfLastYDist >= 0 || yDistance >= 0 && data.sfLastYDist <= 0 ); 
        if (yDirChange) {
            vDistanceAboveLimit = yDirChange(from, to, yDistance, vDistanceAboveLimit, data);
        }

        // Accounting support.
        if (cc.survivalFlyAccountingV) {
            // Currently only for "air" phases.
            // Vertical.
            if (yDirChange && data.sfLastYDist > 0) { // (Double.MAX_VALUE is checked above.)
                // Change to descending phase.
                data.vDistAcc.clear();
                // Allow adding 0.
                data.vDistAcc.add((float) yDistance);
            }
            else if (!data.hasAnyVerVel()) {
                // Here yDistance can be negative and positive.
                if (yDistance != 0D) {
                    data.vDistAcc.add((float) yDistance);
                    final double accAboveLimit = verticalAccounting(yDistance, data.vDistAcc ,tags, "vacc" + (data.isVelocityJumpPhase() ? "dirty" : ""));
                    if (accAboveLimit > vDistanceAboveLimit) {
                        vDistanceAboveLimit = accAboveLimit;
                    }
                }
            }
            else {
                // TODO: Just to exclude source of error, might be redundant.
                data.vDistAcc.clear();
            }
        }
        return vDistanceAboveLimit;
    }

    /**
     * Demand that with time the values decrease.<br>
     * The ActionAccumulator instance must have 3 buckets, bucket 1 is checked against
     * bucket 2, 0 is ignored. [Vertical accounting: applies to both falling and jumping]<br>
     * NOTE: This just checks and adds to tags, no change to acc.
     * 
     * @param yDistance
     * @param acc
     * @param tags
     * @param tag Tag to be added in case of a violation of this sub-check.
     * @return A violation value > 0.001, to be interpreted like a moving violation.
     */
    private static final double verticalAccounting(final double yDistance, final ActionAccumulator acc, final ArrayList<String> tags, final String tag) {
        // TODO: distinguish near-ground moves somehow ?
        // Determine which buckets to check:
        // TODO: One state is checked 3 times vs. different yDiff !?
        final int i1, i2;
        // TODO: use 1st vs. 2nd whenever possible (!) (logics might need to distinguish falling from other ?...).
        //		if (acc.bucketCount(0) == acc.bucketCapacity() &&) {
        //			i1 = 0;
        //			i2 = 1;
        //		}
        //		else {
        i1 = 1;
        i2 = 2;
        //		}
        // TODO: One move earlier: count first vs. second once first is full.
        // TODO: Can all three be related if first one is full ?
        if (acc.bucketCount(i1) > 0 && acc.bucketCount(i2) > 0) {
            final float sc1 = acc.bucketScore(i1);
            final float sc2 = acc.bucketScore(i2);
            final double diff = sc1 - sc2;
            final double aDiff = Math.abs(diff);
            // TODO: Relate this to the fall distance !
            // TODO: sharpen later.
            if (diff >= 0.0 || yDistance > -1.05 && aDiff < 0.0625) {
                // TODO: check vs. sc1 !
                if (yDistance <= -1.05 && sc2 < -10.0 && sc1 < -10.0) { // (aDiff < Math.abs(yDistance) || sc2 < - 10.0f)) {
                    // High falling speeds may pass.
                    // TODO:  high falling speeds may pass within some bounds (!).
                    tags.add(tag + "grace");
                    return 0.0;
                }
                tags.add(tag); // Specific tags?
                if (diff < 0.0 ) {
                    // Note: aDiff should be < 0.0625 here.
                    return Math.max(Math.abs(-0.0625 - diff), 0.001);
                }
                else {
                    return 0.0625 + diff;
                }
            }
        }
        return 0.0;
    }

    /**
     * Check on change of y direction.
     * <br>Note: data.sfLastYDist must not be Double.MAX_VALUE when calling this.
     * @param yDistance
     * @param vDistanceAboveLimit
     * @return vDistanceAboveLimit
     */
    private double yDirChange(final PlayerLocation from, final PlayerLocation to, final double yDistance, double vDistanceAboveLimit, final MovingData data) {
        // TODO: Does this account for velocity in a sufficient way?
        if (yDistance > 0) {
            // TODO: Clear active vertical velocity here ?
            // TODO: Demand consuming queued velocity for valid change (!).
            // Increase
            if (data.toWasReset) {
                tags.add("ychinc");
            }
            else {
                // Moving upwards after falling without having touched the ground.
                if (!data.hasAnyVerVel() && data.bunnyhopDelay < 9 && !(data.fromWasReset && data.sfLastYDist == 0D)) {
                    // TODO: adjust limit for bunny-hop.
                    vDistanceAboveLimit = Math.max(vDistanceAboveLimit, Math.abs(yDistance));
                    tags.add("ychincfly");
                }
                else {
                    tags.add("ychincair");
                }
            }
        }
        else {
            // Decrease
            tags.add("ychdec");
            // Detect low jumping.
            // TODO: sfDirty: Account for actual velocity (demands consuming queued for dir-change(!))!
            if (!data.sfLowJump && !data.sfNoLowJump && data.mediumLiftOff == MediumLiftOff.GROUND &&
                    data.sfLastYDist != Double.MAX_VALUE && data.sfLastYDist > 0.0 && !data.isVelocityJumpPhase()) {
                final double setBackYDistance = from.getY() - data.getSetBackY();
                if (setBackYDistance > 0.0) {
                    // Only count it if the player has actually been jumping (higher than setback).
                    final Player player = from.getPlayer();
                    // Estimate of minimal jump height.
                    double estimate = 1.15;
                    if (data.jumpAmplifier > 0) {
                        // TODO: Could skip this.
                        estimate += 0.5 * getJumpAmplifier(player);
                    }
                    if (setBackYDistance < estimate) {
                        // Low jump, further check if there might have been a reason for low jumping.
                        final double headBumpMargin = Math.max(0.0, 2.0 - from.getEyeHeight()) + from.getyOnGround();
                        if (!from.isHeadObstructed(headBumpMargin) && !to.isHeadObstructed(headBumpMargin)) {
                            tags.add("lowjump_set");
                            data.sfLowJump = true;
                        }
                    }
                }
            } // (Low jump.)
        }
        return vDistanceAboveLimit;
    }

    /**
     * After-failure checks for horizontal distance.
     * 
     * buffers and velocity, also re-check hDist with permissions, if needed.
     * 
     * @param player
     * @param from
     * @param to
     * @param walkSpeed
     * @param hAllowedDistance
     * @param hDistance
     * @param hDistanceAboveLimit
     * @param yDistance
     * @param sprinting
     * @param downStream
     * @param skipPermChecks
     * @param data
     * @param cc
     * @return hAllowedDistance, hDistanceAboveLimit, hFreedom
     */
    private double[] hDistAfterFailure(final Player player, final PlayerLocation from, final PlayerLocation to, final double walkSpeed, double hAllowedDistance, final double hDistance, double hDistanceAboveLimit, final double yDistance, final boolean sprinting, final boolean downStream, final MovingData data, final MovingConfig cc, final boolean skipPermChecks) {

        // TODO: Still not entirely sure about this checking order.
        // TODO: Would quick returns make sense for hDistanceAfterFailure == 0.0?

        // Test bunny early, because it applies often and destroys as little as possible.
        hDistanceAboveLimit = bunnyHop(from, to, hDistance, hAllowedDistance, hDistanceAboveLimit, yDistance, sprinting, data);

        // After failure permission checks ( + speed modifier + sneaking + blocking + speeding) and velocity (!).
        if (hDistanceAboveLimit > 0.0 && !skipPermChecks) {
            // TODO: Most cases these will not apply. Consider redesign to do these last or checking right away and skip here on some conditions.
            hAllowedDistance = getAllowedhDist(player, from, to, sprinting, downStream, hDistance, walkSpeed, data, cc, true);
            hDistanceAboveLimit = hDistance - hAllowedDistance;
            tags.add("permchecks");
        }

        // Check velocity.
        double hFreedom = 0.0; // Horizontal velocity used.
        if (hDistanceAboveLimit > 0.0) {
            // (hDistanceAboveLimit > 0.0)
            hFreedom = data.getHorizontalFreedom();
            if (hFreedom < hDistanceAboveLimit) {
                // Use queued velocity if possible.
                hFreedom += data.useHorizontalVelocity(hDistanceAboveLimit - hFreedom);
            }
            if (hFreedom > 0.0) {
                tags.add("hvel");
                hDistanceAboveLimit = Math.max(0.0, hDistanceAboveLimit - hFreedom);
            }
        }

        // After failure bunny (2nd).
        if (hDistanceAboveLimit > 0) {
            // (Could distinguish tags from above call).
            hDistanceAboveLimit = bunnyHop(from, to, hDistance, hAllowedDistance, hDistanceAboveLimit, yDistance, sprinting, data);
        }

        // Horizontal buffer.
        // TODO: Consider to confine use to "not in air" and similar.
        if (hDistanceAboveLimit > 0.0 && data.sfHorizontalBuffer > 0.0) {
            // Handle buffer only if moving too far.
            // Consume buffer.
            tags.add("hbufuse");
            final double amount = Math.min(data.sfHorizontalBuffer, hDistanceAboveLimit);
            hDistanceAboveLimit -= amount;
            // Ensure we never end up below zero.
            data.sfHorizontalBuffer = Math.max(0.0, data.sfHorizontalBuffer - amount);
        }

        // Add the hspeed tag on violation.
        if (hDistanceAboveLimit > 0.0) {
            tags.add("hspeed");
        }
        return new double[]{hAllowedDistance, hDistanceAboveLimit, hFreedom};
    }

    /**
     * Test bunny hop / bunny fly. Does modify data only if 0.0 is returned.
     * @param from
     * @param to
     * @param hDistance
     * @param hAllowedDistance
     * @param hDistanceAboveLimit
     * @param yDistance
     * @param sprinting
     * @param data
     * @return hDistanceAboveLimit
     */
    private double bunnyHop(final PlayerLocation from, final PlayerLocation to, final double hDistance, final double hAllowedDistance, double hDistanceAboveLimit, final double yDistance, final boolean sprinting, final MovingData data) {
        // Check "bunny fly" here, to not fall over sprint resetting on the way.
        boolean allowHop = true;
        boolean double_bunny = false;

        // TODO: A more state-machine like modeling (hop, slope, states, low-edge).

        // Fly phase.
        if (data.sfLastHDist != Double.MAX_VALUE && data.bunnyhopDelay > 0 && hDistance > walkSpeed) { // * modSprint) {
            // (sfLastHDist may be reset on commands.)
            allowHop = false; // Magic!
            final int hopTime = bunnyHopMax - data.bunnyhopDelay;

            // Increase buffer if hDistance is decreasing properly.
            if (data.sfLastHDist != Double.MAX_VALUE && data.sfLastHDist > hDistance) {
                final double hDistDiff = data.sfLastHDist - hDistance;

                // Bunny slope (downwards, directly after hop but before friction).
                if (data.bunnyhopDelay == bunnyHopMax - 1) {
                    // Ensure relative speed decrease vs. hop is met somehow.
                    if (hDistDiff >= 0.66 * (data.sfLastHDist - hAllowedDistance)) {
                        tags.add("bunnyslope");
                        hDistanceAboveLimit = 0.0;
                    }
                }
                else if (
                        hDistDiff >= data.sfLastHDist / bunnyDivFriction || hDistDiff >= hDistanceAboveLimit / 33.3 || 
                        hDistDiff >= (hDistance - hAllowedDistance) * (1.0 - SurvivalFly.FRICTION_MEDIUM_AIR)
                        ) {
                    // TODO: Confine friction by medium ?
                    // TODO: Also calculate an absolute (minimal) speed decrease over the whole time, at least max - count?
                    tags.add("bunnyfriction");
                    //if (hDistanceAboveLimit <= someThreshold) { // To be covered by bunnyslope.
                    // Speed must decrease by "a lot" at first, then by some minimal amount per event.
                    // TODO: Confine buffer to only be used during low jump phase !?
                    //if (!(data.toWasReset && from.isOnGround() && to.isOnGround())) { // FISHY

                    // Allow the move.
                    hDistanceAboveLimit = 0.0;
                    if (data.bunnyhopDelay == 1 && !to.isOnGround() && !to.isResetCond()) {
                        // ... one move between toonground and liftoff remains for hbuf ...
                        data.bunnyhopDelay ++;
                        tags.add("bunnyfly(keep)");
                    }
                    else {
                        tags.add("bunnyfly(" + data.bunnyhopDelay +")");
                    }
                    //}
                    //}
                }
            }

            // 2x horizontal speed increase detection.
            if (!allowHop && data.sfLastHDist != Double.MAX_VALUE && hDistance - data.sfLastHDist >= walkSpeed * 0.5 && hopTime == 1) {
                if (data.sfLastYDist == 0.0 && (data.fromWasReset || data.toWasReset) && yDistance >= 0.4) {
                    // TODO: Confine to increasing set back y ?
                    tags.add(DOUBLE_BUNNY);
                    allowHop = double_bunny = true;
                }
            }

            // Allow hop for special cases.
            if (!allowHop && (from.isOnGround() || data.noFallAssumeGround)) {
                final double headBumpMargin = Math.max(0.0, 2.0 - from.getEyeHeight()) + from.getyOnGround();
                if (data.bunnyhopDelay <= 6 || from.isHeadObstructed(headBumpMargin) || to.isHeadObstructed(headBumpMargin)) {
                    // TODO: headObstructed: check always and set a flag in data + consider regain buffer?
                    tags.add("ediblebunny");
                    allowHop = true;
                }
            }
        }

        // Check hop (singular peak up to roughly two times the allowed distance).
        // TODO: Needs better modeling.
        if (allowHop && hDistance >= walkSpeed && 
                (hDistance > (((data.sfLastHDist == Double.MAX_VALUE || data.sfLastHDist == 0.0 && data.sfLastYDist == 0.0) ? 1.11 : 1.314)) * hAllowedDistance) 
                && hDistance < 2.15 * hAllowedDistance
                || (yDistance > from.getyOnGround() || hDistance < 2.6 * walkSpeed) && data.sfLastHDist != Double.MAX_VALUE && hDistance > 1.314 * data.sfLastHDist && hDistance < 2.15 * data.sfLastHDist
                ) { // if (sprinting) {
            // TODO: Test bunny spike over all sorts of speeds + attributes.
            // TODO: Allow slightly higher speed on lost ground?
            if (data.mediumLiftOff != MediumLiftOff.LIMIT_JUMP // && yDistance >= 0.4 
                    && (data.sfJumpPhase == 0 && from.isOnGround() || data.sfJumpPhase <= 1 && data.noFallAssumeGround)
                    && !from.isResetCond() && !to.isResetCond()
                    || double_bunny
                    ) {
                // TODO: Jump effect might allow more strictness. 
                // TODO: Expected minimum gain depends on last speed (!).
                // TODO: Speed effect affects hDistanceAboveLimit?
                data.bunnyhopDelay = bunnyHopMax;
                hDistanceAboveLimit = 0D;
                tags.add("bunnyhop");
            }
            else {
                tags.add("bunnyenv");
            }
        }

        return hDistanceAboveLimit;
    }

    /**
     * Legitimate move: increase horizontal buffer somehow.
     * @param hDistance
     * @param amount Positive amount.
     * @param data
     */
    private void hBufRegain(final double hDistance, final double amount, final MovingData data) {
        /*
         * TODO: Consider different concepts: 
         * 			- full resetting with harder conditions.
         * 			- maximum regain amount.
         * 			- reset or regain only every x blocks h distance.
         */
        // TODO: Confine general conditions for buffer regain further (regain in air, whatever)?
        data.sfHorizontalBuffer = Math.min(hBufMax, data.sfHorizontalBuffer + amount);
    }

    /**
     * Inside liquids vertical speed checking.
     * @param from
     * @param to
     * @param toOnGround
     * @param yDistance
     * @param data
     * @return vAllowedDistance, vDistanceAboveLimit
     */
    private double[] vDistLiquid(final PlayerLocation from, final PlayerLocation to, final boolean toOnGround, final double yDistance, final MovingData data) {
        double vAllowedDistance = 0.0;
        double vDistanceAboveLimit = 0.0;
        data.sfNoLowJump = true;
        if (yDistance >= 0) {
            // This is more simple to test.
            // TODO: Friction in water...
            vAllowedDistance = walkSpeed * modSwim + 0.02;
            vDistanceAboveLimit = yDistance - vAllowedDistance;
            if (vDistanceAboveLimit > 0) {
                // Check workarounds.
                if (yDistance <= 0.5) {
                    // TODO: mediumLiftOff: refine conditions (general) , to should be near water level.
                    if (data.mediumLiftOff == MediumLiftOff.GROUND && !BlockProperties.isLiquid(from.getTypeIdAbove()) || !to.isInLiquid() ||  (toOnGround || data.sfLastYDist != Double.MAX_VALUE && data.sfLastYDist - yDistance >= 0.010 || to.isAboveStairs())) {
                        vAllowedDistance = walkSpeed * modSwim + 0.5;
                        vDistanceAboveLimit = yDistance - vAllowedDistance;
                    }
                }

                if (vDistanceAboveLimit > 0) {
                    tags.add("swimup");
                }
            }
        }
        // TODO: else: This is more complex, depends too much on the speed of diving into the medium.
        return new double[]{vAllowedDistance, vDistanceAboveLimit};
    }

    /**
     * On-climbable vertical distance checking.
     * @param from
     * @param fromOnGround
     * @param toOnGround
     * @param yDistance
     * @param data
     * @return vDistanceAboveLimit
     */
    private double vDistClimbable(final Player player, final PlayerLocation from, final boolean fromOnGround, final boolean toOnGround, final double yDistance, final MovingData data) {
        double vDistanceAboveLimit = 0.0;
        data.sfNoLowJump = true;
        // TODO: bring in in-medium accounting.
        //    	// TODO: make these extra checks to the jumpphase thing ?
        //    	if (fromOnGround) vAllowedDistance = climbSpeed + 0.3;
        //    	else vAllowedDistance = climbSpeed;
        //    	vDistanceAboveLimit = Math.abs(yDistance) - vAllowedDistance;
        //    	if (vDistanceAboveLimit > 0) tags.add("vclimb");
        final double jumpHeight = 1.35 + (data.jumpAmplifier > 0 ? (0.6 + data.jumpAmplifier - 1.0) : 0.0);
        // TODO: ladders are ground !
        // TODO: yDistance < 0.0 ?
        if (Math.abs(yDistance) > climbSpeed) {
            if (from.isOnGround(jumpHeight, 0D, 0D, BlockProperties.F_CLIMBABLE)) {
                if (yDistance > MovingUtil.estimateJumpLiftOff(player, data, 0.1)) {
                    tags.add("climbstep");
                    vDistanceAboveLimit = Math.max(vDistanceAboveLimit, Math.abs(yDistance) - climbSpeed);
                }
            } else {
                tags.add("climbspeed");
                vDistanceAboveLimit = Math.max(vDistanceAboveLimit, Math.abs(yDistance) - climbSpeed);
            }
        }
        if (yDistance > 0) {
            if (!fromOnGround && !toOnGround && !data.noFallAssumeGround) {
                // Check if player may climb up.
                // (This does exclude ladders.)
                if (!from.canClimbUp(jumpHeight)) {
                    tags.add("climbdetached");
                    vDistanceAboveLimit = Math.max(vDistanceAboveLimit, yDistance);
                }
            }
        }
        return vDistanceAboveLimit;
    }

    /**
     * In-web vertical distance checking.
     * @param player
     * @param from
     * @param to
     * @param toOnGround
     * @param hDistanceAboveLimit
     * @param yDistance
     * @param now
     * @param data
     * @param cc
     * @return vAllowedDistance, vDistanceAboveLimit
     */
    private double[] vDistWeb(final Player player, final PlayerLocation from, final PlayerLocation to, final boolean toOnGround, final double hDistanceAboveLimit, final double yDistance, final long now, final MovingData data, final MovingConfig cc) {
        double vAllowedDistance = 0.0;
        double vDistanceAboveLimit = 0.0;
        data.sfNoLowJump = true;
        data.jumpAmplifier = 0; // TODO: later maybe fetch.
        // Very simple: force players to descend or stay.
        if (yDistance >= 0.0) {
            if (toOnGround && yDistance <= 0.5) {
                // Step up. Note: Does not take into account jump effect on purpose.
                vAllowedDistance = yDistance;
                if (yDistance > 0.0) {
                    tags.add("web_step");
                }
            }
            else {
                // TODO: Could prevent not moving down if not on ground (or on ladder or in liquid?).
                vAllowedDistance = from.isOnGround() ? 0.1D : 0;
            }
            vDistanceAboveLimit = yDistance - vAllowedDistance;
        }
        else {
            // Descending in web.
            // TODO: Implement something (at least for being in web with the feet or block above)?
        }
        if (cc.survivalFlyCobwebHack && vDistanceAboveLimit > 0.0 && hDistanceAboveLimit <= 0.0) {
            // TODO: Seemed fixed at first by CB/MC, but still does occur due to jumping. 
            if (hackCobweb(player, data, to, now, vDistanceAboveLimit)) {
                return new double[]{Double.MIN_VALUE, Double.MIN_VALUE};
            }
        }
        // TODO: Prevent too fast moving down ?
        if (vDistanceAboveLimit > 0.0) {
            tags.add("vweb");
        }
        return new double[]{vAllowedDistance, vDistanceAboveLimit};
    }

    /**
     * Check if a ground-touch has been lost due to event-sending-frequency or other reasons.<br>
     * This is for ascending only (yDistance >= 0).
     * @param player
     * @param from
     * @param loc 
     * @param to
     * @param hDistance
     * @param yDistance
     * @param sprinting
     * @param data
     * @param cc
     * @return
     */
    private boolean lostGroundAscend(final Player player, final PlayerLocation from, final Location loc, final PlayerLocation to, final double hDistance, final double yDistance, final boolean sprinting, final MovingData data, final MovingConfig cc) {
        final double setBackYDistance = to.getY() - data.getSetBackY();
        // Step height related.
        // TODO: Combine / refine preconditions for step height related.
        // TODO: || yDistance <= jump estimate? 
        if (yDistance <= cc.sfStepHeight && hDistance <= 1.5) { // hDistance is arbitrary, just to confine.
            // Half block step up (definitive).
            // TODO: && hDistance < 0.5  ~  switch to about 2.2 * baseSpeed once available.
            if (setBackYDistance <= Math.max(0.0, 1.3 + 0.2 * data.jumpAmplifier) && to.isOnGround()) {
                // TODO: hDistance > 0.0
                if (data.sfLastYDist < 0.0 || yDistance <= cc.sfStepHeight && from.isOnGround(cc.sfStepHeight - yDistance)) {
                    return applyLostGround(player, from, true, data, "step");
                }
            }
            // Could step up (but might move to another direction, potentially).
            if (data.sfLastYDist < 0.0) { // TODO: <= ?
                // Generic could step.
                // TODO: Possibly confine margin depending on side, moving direction (see client code).
                // TODO: Consider player.getLocation too (!).
                // TODO: Should this also be checked vs. last from?
                if (BlockProperties.isOnGroundShuffled(to.getBlockCache(), from.getX(), from.getY() + cc.sfStepHeight, from.getZ(), to.getX(), to.getY(), to.getZ(), 0.1 + (double) Math.round(from.getWidth() * 500.0) / 1000.0, to.getyOnGround(), 0.0)) {
                    // TODO: Set a data property, so vdist does not trigger (currently: scan for tag)
                    // TODO: !to.isOnGround?
                    return applyLostGround(player, from, false, data, "couldstep");
                }
                // Close by ground miss (client side blocks y move, but allows h move fully/mostly, missing the edge on server side).
                // Possibly confine by more criteria.
                if (!to.isOnGround()) { // TODO: Note, that there may be cases with to on ground (!).
                    if (data.fromX != Double.MAX_VALUE && data.sfLastHDist != Double.MAX_VALUE && data.sfLastYDist != Double.MAX_VALUE) {
                        // (Use covered area to last from.)
                        // TODO: Plausible: last to is about this from?
                        // TODO: Otherwise cap max. amount (seems not really possible, could confine by passable checking).
                        // TODO: Might estimate by the yDist from before last from (cap x2 and y2).
                        // TODO: A ray-tracing version of isOnground?
                        if (lostGroundEdgeAsc(player, from.getBlockCache(), from.getWorld(), from.getX(), from.getY(), from.getZ(), from.getWidth(), from.getyOnGround(), data, "asc1")) {
                            return true;
                        }

                        // Special cases.
                        if (!from.isSamePos(loc)) {
                            // Micro-moves: Re-check with loc instead of from.
                            // TODO: These belong checked as extra moves. The untracked nature of this means potential exploits. 
                            if (lostGroundEdgeAsc(player, from.getBlockCache(), from.getWorld(), loc.getX(), loc.getY(), loc.getZ(), from.getWidth(), from.getyOnGround(), data, "asc3")) {
                                return true;
                            }
                        }
                        else if (yDistance == 0.0 && data.sfLastYDist <= -0.3 && (hDistance <= data.sfLastHDist * 1.1)) {
                            // Similar to couldstep, with 0 y-distance but slightly above any ground nearby (no micro move!).
                            // TODO: (hDistance <= data.sfLastHDist || hDistance <= hAllowedDistance)
                            // TODO: Confining in x/z direction in general: should detect if collided in that direction (then skip the x/z dist <= last time).
                            // TODO: Temporary test (should probably be covered by one of the above instead).
                            // TODO: Code duplication with edgeasc7 below.
                            if (lostGroundEdgeAsc(player, from.getBlockCache(), to.getWorld(), to.getX(), to.getY(), to.getZ(), from.getX(), from.getY(), from.getZ(), hDistance, to.getWidth(), 0.3, data, "asc5")) {
                                return true;
                            }
                        }
                    }
                    else if (from.isOnGround(from.getyOnGround(), 0.0625, 0.0)) {
                        // (Minimal margin.)
                        //data.sfLastAllowBunny = true; // TODO: Maybe a less powerful flag (just skipping what is necessary).
                        return applyLostGround(player, from, false, data, "edgeasc2"); // Maybe true ?
                    }
                }
            }
        }
        // Nothing found.
        return false;
    }

    /**
     * Preconditions move dist is 0, not on ground, last h dist > 0, last y dist < 0.
     * @param player
     * @param from
     * @param loc
     * @param to
     * @param hDistance
     * @param yDistance
     * @param sprinting
     * @param data
     * @param cc
     * @return
     */
    private boolean lostGroundStill(final Player player, final PlayerLocation from, final Location loc, final PlayerLocation to, final double hDistance, final double yDistance, final boolean sprinting, final MovingData data, final MovingConfig cc) {
        if (data.sfLastYDist <= -0.3) {
            // TODO: Code duplication with edgeasc5 above.
            if (lostGroundEdgeAsc(player, from.getBlockCache(), to.getWorld(), to.getX(), to.getY(), to.getZ(), from.getX(), from.getY(), from.getZ(), hDistance, to.getWidth(), 0.3, data, "asc7")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Vertical collision with ground on client side, shifting over an edge with the horizontal move. Using last from from MovingData.
     * @param player
     * @param blockCache
     * @param world
     * @param x1 Target position.
     * @param y1
     * @param z1
     * @param width
     * @param yOnGround
     * @param data
     * @param tag
     * @return
     */
    private final boolean lostGroundEdgeAsc(final Player player, final BlockCache blockCache, final World world, final double x1, final double y1, final double z1, final double width, final double yOnGround, final MovingData data, final String tag) {
        return lostGroundEdgeAsc(player, blockCache, world, x1, y1, z1, data.fromX, data.fromY, data.fromZ, data.sfLastHDist, width, yOnGround, data, tag);
    }

    private final boolean lostGroundEdgeAsc(final Player player, final BlockCache blockCache, final World world, final double x1, final double y1, final double z1, double x2, final double y2, double z2, final double hDistance2, final double width, final double yOnGround, final MovingData data, final String tag) {
        // First: calculate vector towards last from.
        x2 -= x1;
        z2 -= z1;
        // double y2 = data.fromY - y1; // Just for consistency checks (sfLastYDist).
        // Second: cap the size of the extra box (at least horizontal).
        double fMin = 1.0; // Factor for capping.
        if (Math.abs(x2) > hDistance2) {
            fMin = Math.min(fMin, hDistance2 / Math.abs(x2));
        }
        if (Math.abs(z2) > hDistance2) {
            fMin = Math.min(fMin, hDistance2 / Math.abs(z2));
        }
        // TODO: Further / more precise ?
        // Third: calculate end points.
        x2 = fMin * x2 + x1;
        z2 = fMin * z2 + z1;
        // Finally test for ground.
        final double xzMargin = Math.round(width * 500.0) / 1000.0; // Bounding box "radius" at some resolution.
        // (We don't add another xz-margin here, as the move should cover ground.)
        if (BlockProperties.isOnGroundShuffled(blockCache, x1, y1, z1, x2, y1, z2, xzMargin, yOnGround, 0.0)) {
            //data.sfLastAllowBunny = true; // TODO: Maybe a less powerful flag (just skipping what is necessary).
            // TODO: data.fromY for set back is not correct, but currently it is more safe (needs instead: maintain a "distance to ground").
            return applyLostGround(player, new Location(world, x2, y2, z2), true, data, "edge" + tag); // Maybe true ?
        } else {
            return false;
        }
    }

    /**
     * Check if a ground-touch has been lost due to event-sending-frequency or other reasons.<br>
     * This is for descending "mildly" only (-0.5 <= yDistance <= 0).
     * @param player
     * @param from
     * @param to
     * @param hDistance
     * @param yDistance
     * @param sprinting
     * @param data
     * @param cc
     * @return
     */
    private boolean lostGroundDescend(final Player player, final PlayerLocation from, final PlayerLocation to, final double hDistance, final double yDistance, final boolean sprinting, final MovingData data, final MovingConfig cc) {
        // TODO: re-organize for faster exclusions (hDistance, yDistance).
        // TODO: more strict conditions 

        final double setBackYDistance = to.getY() - data.getSetBackY();

        if (data.sfJumpPhase <= 7) {
            // Check for sprinting down blocks etc.
            if (data.sfLastYDist <= yDistance && setBackYDistance < 0 && !to.isOnGround()) {
                // TODO: setbackydist: <= - 1.0 or similar
                // TODO: <= 7 might work with speed II, not sure with above.
                // TODO: account for speed/sprint
                // TODO: account for half steps !?
                if (from.isOnGround(0.6, 0.4, 0.0, 0L) ) {
                    // TODO: further narrow down bounds ?
                    // Temporary "fix".
                    return applyLostGround(player, from, true, data, "pyramid");
                }
            }

            // Check for jumping up strange blocks like flower pots on top of other blocks.
            if (yDistance == 0.0 && data.sfLastYDist > 0.0 && data.sfLastYDist < 0.25 && data.sfJumpPhase <= Math.max(0, 6 + data.jumpAmplifier * 3.0) && setBackYDistance > 1.0 && setBackYDistance < Math.max(0.0, 1.5 + 0.2 * data.jumpAmplifier) && !to.isOnGround()) {
                // TODO: confine by block types ?
                if (from.isOnGround(0.25, 0.4, 0, 0L) ) {
                    // Temporary "fix".
                    //data.sfThisAllowBunny = true;
                    return applyLostGround(player, from, true, data, "ministep");
                }
            }
        }
        // Lost ground while falling onto/over edges of blocks.
        if (yDistance < 0 && hDistance <= 1.5 && data.sfLastYDist < 0.0 && yDistance > data.sfLastYDist && !to.isOnGround()) {
            // TODO: Should this be an extra lost-ground(to) check, setting toOnGround  [for no-fall no difference]?
            // TODO: yDistance <= 0 might be better.
            // Also clear accounting data.
            //			if (to.isOnGround(0.5) || from.isOnGround(0.5)) {
            if (from.isOnGround(0.5, 0.2, 0) || to.isOnGround(0.5, Math.min(0.2, 0.01 + hDistance), Math.min(0.1, 0.01 + -yDistance))) {
                return applyLostGround(player, from, true, data, "edgedesc");
            }
        }

        // Nothing found.
        return false;
    }

    /**
     * Check if a ground-touch has been lost due to event-sending-frequency or other reasons.<br>
     * This is for fast descending only (yDistance < -0.5).
     * @param player
     * @param from
     * @param to
     * @param hDistance
     * @param yDistance
     * @param sprinting
     * @param data
     * @param cc
     * @return
     */
    private boolean lostGroundFastDescend(final Player player, final PlayerLocation from, final PlayerLocation to, final double hDistance, final double yDistance, final boolean sprinting, final MovingData data, final MovingConfig cc) {
        // TODO: re-organize for faster exclusions (hDistance, yDistance).
        // TODO: more strict conditions 
        // Lost ground while falling onto/over edges of blocks.
        if (yDistance > data.sfLastYDist && !to.isOnGround()) {
            // TODO: Should this be an extra lost-ground(to) check, setting toOnGround  [for no-fall no difference]?
            // TODO: yDistance <= 0 might be better.
            // Also clear accounting data.
            // TODO: stairs ?
            // TODO: Can it be safe to only check to with raised margin ? [in fact should be checked from higher yMin down]
            // TODO: Interpolation method (from to)?
            if (from.isOnGround(0.5, 0.2, 0) || to.isOnGround(0.5, Math.min(0.3, 0.01 + hDistance), Math.min(0.1, 0.01 + -yDistance))) {
                // (Usually yDistance should be -0.078)
                return applyLostGround(player, from, true, data, "fastedge");
            }
        }
        return false;
    }

    /**
     * Apply lost-ground workaround.
     * @param player
     * @param refLoc
     * @param setBackSafe If to use the given location as set-back.
     * @param data
     * @param tag Added to "lostground_" as tag.
     * @return Always true.
     */
    private boolean applyLostGround(final Player player, final Location refLoc, final boolean setBackSafe, final MovingData data, final String tag) {
        if (setBackSafe) {
            data.setSetBack(refLoc);
        }
        else {
            // Keep Set-back.
        }
        return applyLostGround(player, data, tag);
    }

    /**
     * Apply lost-ground workaround.
     * @param player
     * @param refLoc
     * @param setBackSafe If to use the given location as set-back.
     * @param data
     * @param tag Added to "lostground_" as tag.
     * @return Always true.
     */
    private boolean applyLostGround(final Player player, final PlayerLocation refLoc, final boolean setBackSafe, final MovingData data, final String tag) {
        // Set the new setBack and reset the jumpPhase.
        if (setBackSafe) {
            data.setSetBack(refLoc);
        }
        else {
            // Keep Set-back.
        }
        return applyLostGround(player, data, tag);
    }

    /**
     * Apply lost-ground workaround (data adjustments and tag).
     * @param player
     * @param refLoc
     * @param setBackSafe If to use the given location as set-back.
     * @param data
     * @param tag Added to "lostground_" as tag.
     * @return Always true.
     */
    private boolean applyLostGround(final Player player, final MovingData data, final String tag) {
        // Reset the jumpPhase.
        // ? set jumpphase to 1 / other, depending on stuff ?
        data.sfJumpPhase = 0;
        data.jumpAmplifier = getJumpAmplifier(player);
        data.clearAccounting();
        // Tell NoFall that we assume the player to have been on ground somehow.
        data.noFallAssumeGround = true;
        tags.add("lostground_" + tag);
        return true;
    }

    /**
     * Violation handling put here to have less code for the frequent processing of check.
     * @param now
     * @param result
     * @param player
     * @param from
     * @param to
     * @param data
     * @param cc
     * @return
     */
    private final Location handleViolation(final long now, final double result, final Player player, final PlayerLocation from, final PlayerLocation to, final MovingData data, final MovingConfig cc)
    {
        // Increment violation level.
        data.survivalFlyVL += result;
        data.sfVLTime = now;
        final ViolationData vd = new ViolationData(this, player, data.survivalFlyVL, result, cc.survivalFlyActions);
        if (vd.needsParameters()) {
            vd.setParameter(ParameterName.LOCATION_FROM, String.format(Locale.US, "%.2f, %.2f, %.2f", from.getX(), from.getY(), from.getZ()));
            vd.setParameter(ParameterName.LOCATION_TO, String.format(Locale.US, "%.2f, %.2f, %.2f", to.getX(), to.getY(), to.getZ()));
            vd.setParameter(ParameterName.DISTANCE, String.format(Locale.US, "%.2f", TrigUtil.distance(from, to)));
            vd.setParameter(ParameterName.TAGS, StringUtil.join(tags, "+"));
        }
        // Some resetting is done in MovingListener.
        if (executeActions(vd)) {
            // Set-back + view direction of to (more smooth).
            return data.getSetBack(to);
        }
        else {
            data.clearAccounting();
            data.sfJumpPhase = 0;
            // Cancelled by other plugin, or no cancel set by configuration.
            return null;
        }
    }

    /**
     * Hover violations have to be handled in this check, because they are handled as SurvivalFly violations (needs executeActions).
     * @param player
     * @param loc
     * @param cc
     * @param data
     */
    protected final void handleHoverViolation(final Player player, final Location loc, final MovingConfig cc, final MovingData data) {
        data.survivalFlyVL += cc.sfHoverViolation;

        // TODO: Extra options for set-back / kick, like vl?
        data.sfVLTime = System.currentTimeMillis();
        final ViolationData vd = new ViolationData(this, player, data.survivalFlyVL, cc.sfHoverViolation, cc.survivalFlyActions);
        if (vd.needsParameters()) {
            vd.setParameter(ParameterName.LOCATION_FROM, String.format(Locale.US, "%.2f, %.2f, %.2f", loc.getX(), loc.getY(), loc.getZ()));
            vd.setParameter(ParameterName.LOCATION_TO, "(HOVER)");
            vd.setParameter(ParameterName.DISTANCE, "0.0(HOVER)");
            vd.setParameter(ParameterName.TAGS, "hover");
        }
        if (executeActions(vd)) {
            // Set-back or kick.
            if (data.hasSetBack()) {
                final Location newTo = data.getSetBack(loc);
                data.prepareSetBack(newTo);
                player.teleport(newTo, TeleportCause.PLUGIN);
            }
            else {
                // Solve by extra actions ? Special case (probably never happens)?
                player.kickPlayer("Hovering?");
            }
        }
        else {
            // Ignore.
        }
    }

    /**
     * Allow accumulating some vls and silently set the player back.
     * 
     * @param player
     * @param data
     * @param cc
     * @param to
     * @param now
     * @param vDistanceAboveLimit
     * @return If to silently set back.
     */
    private final boolean hackCobweb(final Player player, final MovingData data, final PlayerLocation to, 
            final long now, final double vDistanceAboveLimit)
    {
        if (now - data.sfCobwebTime > 3000) {
            data.sfCobwebTime = now;
            data.sfCobwebVL = vDistanceAboveLimit * 100D;
        } else {
            data.sfCobwebVL += vDistanceAboveLimit * 100D;
        }
        if (data.sfCobwebVL < 550) { // Totally random !
            // Silently set back.
            if (!data.hasSetBack()) {
                data.setSetBack(player.getLocation(useLoc)); // ? check moment of call.
                useLoc.setWorld(null);
            }
            data.sfJumpPhase = 0;
            data.sfLastYDist = data.sfLastHDist = Double.MAX_VALUE;
            return true;
        } else {
            return false;
        }
    }

    /**
     * This is set with PlayerToggleSneak, to be able to distinguish players that are really sneaking from players that are set sneaking by a plugin. 
     * @param player
     * @param sneaking
     */
    public void setReallySneaking(final Player player, final boolean sneaking) {
        if (sneaking) reallySneaking.add(player.getName());
        else reallySneaking.remove(player.getName());
    }


    /**
     * Determine "some jump amplifier": 1 is jump boost, 2 is jump boost II. <br>
     * NOTE: This is not the original amplifier value (use mcAccess for that).
     * @param mcPlayer
     * @return
     */
    protected final double getJumpAmplifier(final Player player) {
        final double amplifier = mcAccess.getJumpAmplifier(player);
        if (amplifier == Double.NEGATIVE_INFINITY) return 0D;
        else return 1D + amplifier;
    }

    /**
     * Syso debug output.
     * @param player
     * @param data
     * @param cc
     * @param hDistance
     * @param hAllowedDistance
     * @param yDistance
     * @param vAllowedDistance
     * @param fromOnGround
     * @param resetFrom
     * @param toOnGround
     * @param resetTo
     */
    private void outputDebug(final Player player, final PlayerLocation to, final MovingData data, final MovingConfig cc, 
            final double hDistance, final double hAllowedDistance, final double hFreedom, final double yDistance, final double vAllowedDistance,
            final boolean fromOnGround, final boolean resetFrom, final boolean toOnGround, final boolean resetTo) {
        // TODO: Show player name once (!)
        final StringBuilder builder = new StringBuilder(500);
        final String hBuf = (data.sfHorizontalBuffer < 1.0 ? ((" hbuf=" + StringUtil.fdec3.format(data.sfHorizontalBuffer))) : "");
        final String lostSprint = (data.lostSprintCount > 0 ? (" lostSprint=" + data.lostSprintCount) : "");
        final String hVelUsed = hFreedom > 0 ? " hVelUsed=" + StringUtil.fdec3.format(hFreedom) : "";
        builder.append(player.getName() + " SurvivalFly\nground: " + (data.noFallAssumeGround ? "(assumeonground) " : "") + (fromOnGround ? "onground -> " : (resetFrom ? "resetcond -> " : "--- -> ")) + (toOnGround ? "onground" : (resetTo ? "resetcond" : "---")) + ", jumpphase: " + data.sfJumpPhase);
        final String dHDist = (BuildParameters.debugLevel > 0 && data.sfLastHDist != Double.MAX_VALUE && Math.abs(data.sfLastHDist - hDistance) > 0.0005) ? ("(" + (hDistance > data.sfLastHDist ? "+" : "") + StringUtil.fdec3.format(hDistance - data.sfLastHDist) + ")") : "";
        builder.append("\n" + " hDist: " + StringUtil.fdec3.format(hDistance) + dHDist + " / " +  StringUtil.fdec3.format(hAllowedDistance) + hBuf + lostSprint + hVelUsed + " , vDist: " +  StringUtil.fdec3.format(yDistance) + " (" + StringUtil.fdec3.format(to.getY() - data.getSetBackY()) + " / " +  StringUtil.fdec3.format(vAllowedDistance) + "), sby=" + (data.hasSetBack() ? data.getSetBackY() : "?"));
        data.logVerticalFreedom(builder);
        //		if (data.horizontalVelocityCounter > 0 || data.horizontalFreedom >= 0.001) {
        //			builder.append("\n" + player.getName() + " horizontal freedom: " +  StringUtil.fdec3.format(data.horizontalFreedom) + " (counter=" + data.horizontalVelocityCounter +"/used="+data.horizontalVelocityUsed);
        //		}
        data.addHorizontalVelocity(builder);
        if (!resetFrom && !resetTo) {
            if (cc.survivalFlyAccountingV && data.vDistAcc.count() > data.vDistAcc.bucketCapacity()) builder.append("\n" + " vacc=" + data.vDistAcc.toInformalString());
        }
        if (player.isSleeping()) tags.add("sleeping");
        if (player.getFoodLevel() <= 5 && player.isSprinting()) {
            // Exception: does not take into account latency.
            tags.add("lowfoodsprint");
        }
        if (!tags.isEmpty()) builder.append("\n" + " tags: " + StringUtil.join(tags, "+"));
        builder.append("\n");
        //		builder.append(data.stats.getStatsStr(false));
        NCPAPIProvider.getNoCheatPlusAPI().getLogManager().debug(Streams.TRACE_FILE, builder.toString());
    }

    private void logPostViolationTags(final Player player) {
        NCPAPIProvider.getNoCheatPlusAPI().getLogManager().debug(Streams.TRACE_FILE, player.getName() + " SurvivalFly Post violation handling tag update:\n" + StringUtil.join(tags, "+"));
    }

}
