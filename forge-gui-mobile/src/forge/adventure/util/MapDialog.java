package forge.adventure.util;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Timer;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TypingAdapter;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Forge;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.DialogData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.stage.MapStage;
import forge.card.ColorSet;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.sound.SoundSystem;
import forge.util.Localizer;
import org.apache.commons.lang3.tuple.Pair;

/**
 * MapDialog
 * Implements a dialogue/event tree for dialogs.
 */

public class MapDialog {
    private final MapStage stage;
    private Array<DialogData> data;
    private final int parentID;
    private final static float WIDTH = 250f;
    static private final String defaultJSON = "[\n" +
            "  {\n" +
            "    \"effect\":[],\n" +
            "    \"name\":\"Error\",\n" +
            "    \"text\":\"This is a fallback dialog.\\nPlease check Forge logs for errors.\",\n" +
            "    \"condition\":[],\n" +
            "    \"options\":[\n" +
            "        { \"name\":\"OK\" }\n" +
            "    ]\n" +
            "  }\n" +
            "]";


    public MapDialog(String S, MapStage stage, int parentID) {
        this.stage = stage;
        this.parentID = parentID;
        try {

            if (S.isEmpty()) {
                System.err.print("Dialog error. Dialog property is empty.\n");
                this.data = JSONStringLoader.parse(Array.class, DialogData.class, defaultJSON, defaultJSON);
                return;
            }
            this.data = JSONStringLoader.parse(Array.class, DialogData.class, S, defaultJSON);
        } catch (Exception exception) {
            exception.printStackTrace();

        }
    }

    Pair<FileHandle, Music> audio = null;
    float fade = 1f;

    void unload() {
        if (audio != null) {
            audio.getRight().setOnCompletionListener(null);
            audio.getRight().stop();
            Forge.getAssets().manager().unload(audio.getLeft().path());
            audio = null;
        }
    }

    void disposeAudio() {
        disposeAudio(false);
    }

    void disposeAudio(boolean fadeout) {
        if (fadeout) {
            final float[] v = {1f};
            for (int i = 10; i > 1; i--) {
                float delay = i * 0.1f;
                float j = i;
                Timer.schedule(new Timer.Task() {
                    @Override
                    public void run() {
                        v[0] -= 0.1f;
                        if (v[0] < 0.1f)
                            v[0] = 0.1f;
                        if (audio != null && j == 2) {
                            unload();
                        } else if (audio != null && j == 10) {
                            audio.getRight().setVolume(v[0]);
                        }
                    }
                }, delay);
            }
        } else {
            unload();
        }
    }

    private void loadDialog(DialogData dialog) { //Displays a dialog with dialogue and possible choices.
        setEffects(dialog.action);
        Dialog D = stage.getDialog();
        Localizer L = Forge.getLocalizer();
        D.getContentTable().clear();
        D.getButtonTable().clear(); //Clear tables to start fresh.
        D.clearListeners();
        String text; //Check for localized string (locname), otherwise print text.
        if (dialog.loctext != null && !dialog.loctext.isEmpty()) text = L.getMessage(dialog.loctext);
        else text = dialog.text;
        disposeAudio();
        if (dialog.voiceFile != null) {
            FileHandle file = Gdx.files.absolute(Config.instance().getFilePath(dialog.voiceFile));
            if (file.exists()) {
                audio = Pair.of(file, Forge.getAssets().getMusic(file));
            }
            if (audio != null) {
                int vol = FModel.getPreferences().getPrefInt(ForgePreferences.FPref.UI_VOL_MUSIC);
                if (vol > 0) {
                    fadeOut();
                    audio.getRight().play();
                }
            } else {
                fadeIn();
            }
        } else {
            fadeIn();
        }
        TypingLabel A = Controls.newTypingLabel(text);
        A.setWrap(true);
        Array<TextraButton> buttons = new Array<>();
        A.setTypingListener(new TypingAdapter() {
            @Override
            public void end() {
                float delay = 0.09f;
                for (TextraButton button : buttons) {
                    Timer.schedule(new Timer.Task() {
                        @Override
                        public void run() {
                            button.setVisible(true);
                        }
                    }, delay);
                    delay += 0.10f;
                }
            }
        });
        D.getContentTable().add(A).width(WIDTH); //Add() returns a Cell, which is what the width is being applied to.
        if (dialog.options != null) {
            int i = 0;
            for (DialogData option : dialog.options) {
                if (isConditionOk(option.condition)) {
                    String name; //Get localized label if present.
                    if (option.locname != null && !option.locname.isEmpty()) name = L.getMessage(option.locname);
                    else name = option.name;
                    TextraButton B = Controls.newTextButton(name, () -> loadDialog(option));
                    B.getTextraLabel().setWrap(true); //We want this to wrap in case it's a wordy choice.
                    buttons.add(B);
                    B.setVisible(false);
                    D.getButtonTable().add(B).width(WIDTH - 10); //The button table also returns a Cell when adding.
                    //TODO: Reducing the space a tiny bit could help. But should be fine as long as there aren't more than 4-5 options.
                    D.getButtonTable().row(); //Add a row. Tried to allow a few per row but it was a bit erratic.
                    i++;
                }
            }
            D.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    A.skipToTheEnd();
                    super.clicked(event, x, y);
                }
            });
            if (i == 0)
                stage.hideDialog();
            else
                stage.showDialog();
        } else {
            stage.hideDialog();
        }
    }

    void fadeIn() {
        disposeAudio(true);
        if (fade >= 1f)
            return;
        for (int i = 10; i > 1; i--) {
            float delay = i * 0.1f;
            Timer.schedule(new Timer.Task() {
                @Override
                public void run() {
                    fade += 0.1f;
                    if (fade > 1f)
                        fade = 1f;
                    SoundSystem.instance.fadeModifier(fade);
                }
            }, delay);
        }
    }

    void fadeOut() {
        for (int i = 10; i > 1; i--) {
            float delay = i * 0.1f;
            Timer.schedule(new Timer.Task() {
                @Override
                public void run() {
                    fade -= 0.1f;
                    if (fade < 0.1f)
                        fade = 0.1f;
                    SoundSystem.instance.fadeModifier(fade);
                }
            }, delay);
        }
    }

    public void activate() { //Method for actors to show their dialogues.
        for (DialogData dialog : data) {
            if (isConditionOk(dialog.condition)) {
                loadDialog(dialog);
            }
        }
    }

    void setEffects(DialogData.ActionData[] data) {
        if (data == null) return;
        for (DialogData.ActionData E : data) {
            if (E.removeItem != null) { //Removes an item from the player's inventory.
                Current.player().removeItem(E.removeItem);
            }
            if (E.addItem != null) { //Gives an item to the player.
                Current.player().addItem(E.addItem);
            }
            if (E.addLife != 0) { //Gives (positive or negative) life to the player. Cannot go over max health.
                Current.player().heal(E.addLife);
            }
            if (E.addGold != 0) { //Gives (positive or negative) gold to the player.
                if (E.addGold > 0) Current.player().giveGold(E.addGold);
                else Current.player().takeGold(-E.addGold);
            }
            if (E.deleteMapObject != 0) { //Removes a dummy object from the map.
                if (E.deleteMapObject < 0) stage.deleteObject(parentID);
                else stage.deleteObject(E.deleteMapObject);
            }
            if (E.battleWithActorID != 0) { //Starts a battle with the given enemy ID.
                if (E.battleWithActorID < 0) stage.beginDuel(stage.getEnemyByID(parentID));
                else stage.beginDuel(stage.getEnemyByID(E.battleWithActorID));
            }
            if (E.giveBlessing != null) { //Gives a blessing for your next battle.
                Current.player().addBlessing(E.giveBlessing);
            }
            if (E.setColorIdentity != null && !E.setColorIdentity.isEmpty()) { //Sets color identity (use sparingly)
                Current.player().setColorIdentity(E.setColorIdentity);
            }
            if (E.setQuestFlag != null && !E.setQuestFlag.key.isEmpty()) { //Set a quest to given value.
                Current.player().setQuestFlag(E.setQuestFlag.key, E.setQuestFlag.val);
            }
            if (E.advanceQuestFlag != null && !E.advanceQuestFlag.isEmpty()) { //Increase a given quest flag by 1.
                Current.player().advanceQuestFlag(E.advanceQuestFlag);
            }
            if (E.setMapFlag != null && !E.setMapFlag.key.isEmpty()) { //Set a local quest to given value.
                stage.setQuestFlag(E.setMapFlag.key, E.setMapFlag.val);
            }
            if (E.advanceMapFlag != null && !E.advanceMapFlag.isEmpty()) { //Increase a given local quest flag by 1.
                stage.advanceQuestFlag(E.advanceMapFlag);
            }
            if (E.setEffect != null) { //Replace current effects.
                EnemySprite EN = stage.getEnemyByID(parentID);
                EN.effect = E.setEffect;
            }
        }
    }

    public boolean canShow() {
        if (data == null) return false;
        for (DialogData dialog : data) {
            if (isConditionOk(dialog.condition)) {
                return true;
            }
        }
        return false;
    }

    public boolean isConditionOk(DialogData.ConditionData[] data) {
        if (data == null) return true;
        AdventurePlayer player = Current.player();
        for (DialogData.ConditionData condition : data) {
            //TODO:Check for card in inventory.
            if (condition.item != null && !condition.item.isEmpty()) { //Check for an item in player's inventory.
                if (!player.hasItem(condition.item)) {
                    if (!condition.not) return false; //Only return on a false.
                } else if (condition.not) return false;
            }
            if (condition.colorIdentity != null && !condition.colorIdentity.isEmpty()) { //Check for player's color ID.
                if (player.getColorIdentity().hasAllColors(ColorSet.fromNames(condition.colorIdentity.toCharArray()).getColor())) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
            if (condition.hasGold != 0) { //Check for at least X gold.
                if (player.getGold() < condition.hasGold) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
            if (condition.hasLife != 0) { //Check for at least X life..
                if (player.getLife() < condition.hasLife + 1) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
            if (condition.hasBlessing != null && !condition.hasBlessing.isEmpty()) { //Check for a named blessing.
                if (!player.hasBlessing(condition.hasBlessing)) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
            if (condition.actorID != 0) { //Check for actor ID.
                if (!stage.lookForID(condition.actorID)) {
                    if (!condition.not) return false; //Same as above.
                } else if (condition.not) return false;
            }
            if (condition.getQuestFlag != null) {
                String key = condition.getQuestFlag.key;
                String cond = condition.getQuestFlag.op;
                int val = condition.getQuestFlag.val;
                int QF = player.getQuestFlag(key);
                if (!player.checkQuestFlag(key)) return false; //If the quest is not ongoing, stop.
                if (!checkFlagCondition(QF, cond, val)) {
                    if (!condition.not) return false;
                } else {
                    if (condition.not) return false;
                }
            }
            if (condition.checkQuestFlag != null && !condition.checkQuestFlag.isEmpty()) {
                if (!player.checkQuestFlag(condition.checkQuestFlag)) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
            if (condition.getMapFlag != null) {
                String key = condition.getMapFlag.key;
                String cond = condition.getMapFlag.op;
                int val = condition.getMapFlag.val;
                int QF = stage.getQuestFlag(key);
                if (!stage.checkQuestFlag(key)) return false; //If the quest is not ongoing, stop.
                if (!checkFlagCondition(QF, cond, val)) {
                    if (!condition.not) return false;
                } else {
                    if (condition.not) return false;
                }
            }
            if (condition.checkMapFlag != null && !condition.checkMapFlag.isEmpty()) {
                if (!stage.checkQuestFlag(condition.checkMapFlag)) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
        }
        return true;
    }

    private boolean checkFlagCondition(int flag, String condition, int value) {
        switch (condition.toUpperCase()) {
            default:
            case "EQUALS":
            case "EQUAL":
            case "=":
                if (flag == value) return true;
            case "LESSTHAN":
            case "<":
                if (flag < value) return true;
            case "MORETHAN":
            case ">":
                if (flag > value) return true;
            case "LE_THAN":
            case "<=":
                if (flag <= value) return true;
            case "ME_THAN":
            case ">=":
                if (flag >= value) return true;
        }
        return false;
    }
}
