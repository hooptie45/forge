/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.player;

import java.util.Collections;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import forge.card.CardType;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.GameObject;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardPlayOption;
import forge.game.cost.Cost;
import forge.game.cost.CostPayment;
import forge.game.keyword.KeywordInterface;
import forge.game.mana.ManaPool;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.spellability.SpellAbility;
import forge.game.zone.Zone;
import forge.util.Localizer;

/**
 * <p>
 * SpellAbility_Requirements class.
 * </p>
 *
 * @author Forge
 * @version $Id: HumanPlaySpellAbility.java 24317 2014-01-17 08:32:39Z Max mtg $
 */
public class HumanPlaySpellAbility {
    private final PlayerControllerHuman controller;
    private SpellAbility ability;

    public HumanPlaySpellAbility(final PlayerControllerHuman controller0, final SpellAbility ability0) {
        controller = controller0;
        ability = ability0;
    }

    public final boolean playAbility(final boolean mayChooseTargets, final boolean isFree, final boolean skipStack) {
        final Player human = ability.getActivatingPlayer();
        final Game game = human.getGame();

        // CR 401.5: freeze top library cards until cast/activated so player can't cheat and see the next
        if (!skipStack) {
            game.setTopLibsCast();
        }

        // used to rollback
        Zone fromZone = null;
        int zonePosition = 0;
        final ManaPool manapool = human.getManaPool();

        final Card c = ability.getHostCard();
        final CardPlayOption option = c.mayPlay(ability.getMayPlay());

        boolean manaTypeConversion = false;
        boolean manaColorConversion = false;

        boolean keywordColor = false;
        // freeze Stack. No abilities should go onto the stack while I'm filling requirements.
        game.getStack().freezeStack();

        if (ability.isSpell() && !c.isCopiedSpell()) {
            fromZone = game.getZoneOf(c);
            if (fromZone != null) {
                zonePosition = fromZone.getCards().indexOf(c);
            }
            ability.setHostCard(game.getAction().moveToStack(c, ability));
            ability.changeText();
        }

        if (!ability.isCopied()) {
            ability.resetPaidHash();
            ability.setPaidLife(0);
        }

        ability = GameActionUtil.addExtraKeywordCost(ability);

        if (ability.isSpell() && !ability.isCopied()) { // These hidden keywords should only apply on the Stack
            if (ability.getHostCard().hasKeyword("May spend mana as though it were mana of any type to cast CARDNAME")
                    || (option != null && option.isIgnoreManaCostType())) {
                manaTypeConversion = true;
            } else if (ability.getHostCard().hasKeyword("May spend mana as though it were mana of any color to cast CARDNAME")
                    || (option != null && option.isIgnoreManaCostColor())) {
                manaColorConversion = true;
            }
        }

        final boolean playerManaConversion = human.hasManaConversion()
                && human.getController().confirmAction(ability, null, "Do you want to spend mana as though it were mana of any color to pay the cost?", null);

        Cost abCost = ability.getPayCosts();
        CostPayment payment = new CostPayment(abCost, ability);

        // TODO Apply this to the SAStackInstance instead of the Player
        if (manaTypeConversion) {
            AbilityUtils.applyManaColorConversion(payment, MagicColor.Constant.ANY_TYPE_CONVERSION);
        } else if (manaColorConversion) {
            AbilityUtils.applyManaColorConversion(payment, MagicColor.Constant.ANY_COLOR_CONVERSION);
        }

        if (playerManaConversion) {
            AbilityUtils.applyManaColorConversion(manapool, MagicColor.Constant.ANY_COLOR_CONVERSION);
            human.incNumManaConversion();
        }

        if (option != null && option.isIgnoreSnowSourceManaCostColor()) {
            payment.setSnowForColor(true);
        }

        if (ability.isActivatedAbility()) {
            final Map<String, String> params = Maps.newHashMap();

            for (KeywordInterface inst : c.getKeywords()) {
                String keyword = inst.getOriginal();
                if (keyword.startsWith("ManaConvert")) {
                    final String[] k = keyword.split(":");
                    params.put("ManaConversion", k[1]);
                    keywordColor = true;
                }
            }
            if (ability.hasParam("ActivateIgnoreColor")) {
                params.put("ManaConversion", ability.getParam("ActivateIgnoreColor"));
                manaColorConversion = true;
            }

            if (keywordColor || manaColorConversion) {
                AbilityUtils.applyManaColorConversion(payment, params);
            }
        }

        // reset is also done early here, because if an ability is canceled from targeting it might otherwise lead to refunding mana from earlier cast
        ability.clearManaPaid();

        // This line makes use of short-circuit evaluation of boolean values, that is each subsequent argument
        // is only executed or evaluated if the first argument does not suffice to determine the value of the expression
        // because of Selective Snare do announceType first
        final boolean prerequisitesMet = announceType()
                && announceValuesLikeX()
                && (!mayChooseTargets || ability.setupTargets()) // if you can choose targets, then do choose them.
                && ability.canCastTiming(human)
                && ability.checkRestrictions(human)
                && ability.isLegalAfterStack()
                && (isFree || payment.payCost(new HumanCostDecision(controller, human, ability, false, ability.getHostCard())));

        game.clearTopLibsCast(ability);

        if (!prerequisitesMet) {
            if (!ability.isTrigger()) {
                GameActionUtil.rollbackAbility(ability, fromZone, zonePosition, payment, c);
                if (ability.getHostCard().isMadness()) {
                    // if a player failed to play madness cost, move the card to graveyard
                    Card newCard = game.getAction().moveToGraveyard(c, null);
                    newCard.setDiscarded(true);
                }
            } else {
                payment.refundPayment();
            }

            if (manaTypeConversion || manaColorConversion || keywordColor) {
                manapool.restoreColorReplacements();
            }
            if (playerManaConversion) {
                manapool.restoreColorReplacements();
                human.decNumManaConversion();
            }
            return false;
        }

        if (isFree || payment.isFullyPaid()) {
            //track when planeswalker ultimates are activated
            human.getAchievementTracker().onSpellAbilityPlayed(ability);

            if (skipStack) {
                AbilityUtils.resolve(ability);
                // Should unfreeze stack
                game.getStack().unfreezeStack();
            } else {
                ensureAbilityHasDescription(ability);
                game.getStack().addAndUnfreeze(ability);
            }

            // no worries here. The same thread must resolve, and by this moment ability will have been resolved already
            // Triggers haven't resolved yet ??
            if (mayChooseTargets && !ability.hasParam("TargetsAtRandom")) {
                ability.clearTargets();
            }
            if (manaTypeConversion || manaColorConversion || keywordColor) {
                manapool.restoreColorReplacements();
            }
        }
        return true;
    }

    private boolean announceValuesLikeX() {
        if (ability.isCopied() || ability.isWrapper()) { return true; } //don't re-announce for spell copies

        boolean needX = true;
        final Cost cost = ability.getPayCosts();
        final PlayerController controller = ability.getActivatingPlayer().getController();
        final Card card = ability.getHostCard();

        // Announcing Requirements like Choosing X or Multikicker
        // SA Params as comma delimited list
        final String announce = ability.getParam("Announce");
        if (announce != null) {
            for (final String aVar : announce.split(",")) {
                final String varName = aVar.trim();

                final Integer value = controller.announceRequirements(ability, varName);
                if (value == null) {
                    return false;
                }

                if ("X".equalsIgnoreCase(varName)) {
                    needX = false;
                    ability.setXManaCostPaid(value);
                } else {
                    ability.setSVar(varName, value.toString());
                    if ("Multikicker".equals(varName)) {
                        card.setKickerMagnitude(value);
                    } else if ("Pseudo-multikicker".equals(varName)) {
                        card.setPseudoMultiKickerMagnitude(value);
                    } else {
                        card.setSVar(varName, value.toString());
                    }
                }
            }
        }

        if (needX) {
            if (cost.hasXInAnyCostPart()) {
                final String sVar = ability.getSVar("X"); //only prompt for new X value if card doesn't determine it another way
                if ("Count$xPaid".equals(sVar) || sVar.isEmpty()) {
                    final Integer value = controller.announceRequirements(ability, "X");
                    if (value == null) {
                        return false;
                    }
                    ability.setXManaCostPaid(value);
                }
            } else {
                ability.setXManaCostPaid(null);
            }
        }
        return true;
    }

    // Announcing Requirements like choosing creature type or number
    private boolean announceType() {
        if (ability.isCopied()) { return true; } //don't re-announce for spell copies

        final String announce = ability.getParam("AnnounceType");
        final PlayerController pc = ability.getActivatingPlayer().getController();
        if (announce != null) {
            for (final String aVar : announce.split(",")) {
                final String varName = aVar.trim();
                if ("CreatureType".equals(varName)) {
                    final String choice = pc.chooseSomeType("Creature", ability, CardType.Constant.CREATURE_TYPES, Collections.emptyList());
                    ability.getHostCard().setChosenType(choice);
                }
                if ("ChooseNumber".equals(varName)) {
                    final int min = Integer.parseInt(ability.getParam("Min"));
                    final int max = Integer.parseInt(ability.getParam("Max"));
                    final int i = ability.getActivatingPlayer().getController().chooseNumber(ability,
                            Localizer.getInstance().getMessage("lblChooseNumber") , min, max);
                    ability.getHostCard().setChosenNumber(i);
                }
                if ("Opponent".equals(varName)) {
                    Player opp = ability.getActivatingPlayer().getController().chooseSingleEntityForEffect(ability.getActivatingPlayer().getOpponents(), ability, Localizer.getInstance().getMessage("lblChooseAnOpponent"), null);
                    ability.getHostCard().setChosenPlayer(opp);
                }
            }
        }
        return true;
    }

    private static void ensureAbilityHasDescription(final SpellAbility ability) {
        if (!StringUtils.isBlank(ability.getStackDescription())) {
            return;
        }

        // For older abilities that don't setStackDescription set it here
        final StringBuilder sb = new StringBuilder();
        sb.append(ability.getHostCard().getName());
        if (ability.usesTargeting()) {
            final Iterable<GameObject> targets = ability.getTargets();
            if (!Iterables.isEmpty(targets)) {
                sb.append(" - Targeting ");
                for (final GameObject o : targets) {
                    sb.append(o.toString()).append(" ");
                }
            }
        }

        ability.setStackDescription(sb.toString());
    }
}
