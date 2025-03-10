package forge.adventure.stage;


import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.controllers.Controllers;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.maps.MapLayer;
import com.badlogic.gdx.maps.MapObject;
import com.badlogic.gdx.maps.MapProperties;
import com.badlogic.gdx.maps.objects.RectangleMapObject;
import com.badlogic.gdx.maps.tiled.TiledMap;
import com.badlogic.gdx.maps.tiled.TiledMapTileLayer;
import com.badlogic.gdx.maps.tiled.objects.TiledMapTileMapObject;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Scaling;
import com.badlogic.gdx.utils.Timer;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TextraLabel;
import com.github.tommyettinger.textra.TypingAdapter;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Forge;
import forge.adventure.character.*;
import forge.adventure.data.*;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.*;
import forge.adventure.util.*;
import forge.adventure.world.WorldSave;
import forge.assets.FImageComplex;
import forge.assets.FSkinImage;
import forge.card.CardRenderer;
import forge.card.ColorSet;
import forge.deck.Deck;
import forge.deck.DeckProxy;
import forge.game.GameType;
import forge.gui.FThreads;
import forge.screens.TransitionScreen;
import forge.sound.SoundEffectType;
import forge.sound.SoundSystem;

import java.time.LocalDate;
import java.util.*;


/**
 * Stage to handle tiled maps for points of interests
 */
public class MapStage extends GameStage {
    public static MapStage instance;
    final Array<MapActor> actors = new Array<>();

    TiledMap map;
    Array<Rectangle> collisionRect = new Array<>();
    private boolean isInMap = false;
    MapLayer spriteLayer;
    private PointOfInterestChanges changes;
    private EnemySprite currentMob;
    private final Vector2 oldPosition = new Vector2();//todo
    private final Vector2 oldPosition2 = new Vector2();
    private final Vector2 oldPosition3 = new Vector2();
    private final Vector2 oldPosition4 = new Vector2();
    private boolean isLoadingMatch = false;
    //private HashMap<String, Byte> mapFlags = new HashMap<>(); //Stores local map flags. These aren't available outside this map.

    private final Dialog dialog;
    private Stage dialogStage;
    private boolean dialogOnlyInput;

    //Map properties.
    //These maps are defined as embedded properties within the Tiled maps.
    private EffectData effect;             //"Dungeon Effect": Character Effect applied to all adversaries within the map.
    private boolean preventEscape = false; //Prevents player from escaping the dungeon by any means that aren't an exit.
    private final Array<TextraButton> dialogButtonMap = new Array<>();

    public InputEvent eventTouchDown, eventTouchUp;
    TextraButton selectedKey;
    private boolean respawnEnemies;
    private boolean canFailDungeon = false;
    protected ArrayList<EnemySprite> enemies = new ArrayList<>();
    protected Map<Integer, Vector2> waypoints = new HashMap<>();

    public boolean getDialogOnlyInput() {
        return dialogOnlyInput;
    }

    public Dialog getDialog() {
        return dialog;
    }

    public boolean canEscape() {
        return !preventEscape;
    } //Check if escape is possible.

    public void clearIsInMap() {
        isInMap = false;
        effect = null; //Reset effect so battles outside the dungeon don't use the last visited dungeon's effects.
        preventEscape = false;
        GameHUD.getInstance().showHideMap(true);
    }

    public void draw(Batch batch) {
        //Camera camera = getCamera() ;
        //camera.update();
        //update camera after all layers got drawn
        if (!getRoot().isVisible()) return;
        getRoot().draw(batch, 1);
    }

    public MapLayer getSpriteLayer() {
        return spriteLayer;
    }

    public PointOfInterestChanges getChanges() {
        return changes;
    }

    private MapStage() {
        dialog = Controls.newDialog("");
        eventTouchDown = new InputEvent();
        eventTouchDown.setPointer(-1);
        eventTouchDown.setType(InputEvent.Type.touchDown);
        eventTouchUp = new InputEvent();
        eventTouchUp.setPointer(-1);
        eventTouchUp.setType(InputEvent.Type.touchUp);
    }

    public static MapStage getInstance() {
        return instance == null ? instance = new MapStage() : instance;
    }

    public void addMapActor(MapObject obj, MapActor newActor) {
        newActor.setWidth(Float.parseFloat(obj.getProperties().get("width").toString()));
        newActor.setHeight(Float.parseFloat(obj.getProperties().get("height").toString()));
        newActor.setX(Float.parseFloat(obj.getProperties().get("x").toString()));
        newActor.setY(Float.parseFloat(obj.getProperties().get("y").toString()));
        actors.add(newActor);
        foregroundSprites.addActor(newActor);
    }

    public void addMapActor(MapActor newActor) {
        actors.add(newActor);
        foregroundSprites.addActor(newActor);
    }

    @Override
    public boolean isColliding(Rectangle adjustedBoundingRect) {
        for (Rectangle collision : collisionRect) {
            if (collision.overlaps(adjustedBoundingRect)) {
                return true;
            }
        }
        return false;
    }

    public float lengthWithoutCollision(EnemySprite mob, Vector2 end){
        Vector2 start = mob.pos();
        Vector2 safeVector = start.cpy().add(end);

        int segmentsToCheck = 100;
        float safeLen = safeVector.len();
        float partialLength = safeLen / segmentsToCheck;

        for (Rectangle collision : collisionRect) {
            float uncollidedLen = 0.0f;
            while (uncollidedLen < safeLen){
                Rectangle mRect = new Rectangle(mob.navigationBoundingRect());
                Vector2 testVector = new Vector2(end).setLength(uncollidedLen + partialLength);
                mRect.setPosition(mRect.x+testVector.x, mRect.y + testVector.y);
                if (mRect.overlaps(collision)){
                    break;
                }
                uncollidedLen += partialLength;
            }
            safeLen = Math.min(safeLen, uncollidedLen);
        }

        return safeLen;
    }


    @Override
    public void prepareCollision(Vector2 pos, Vector2 direction, Rectangle boundingRect) {

    }


    Group collisionGroup;

    @Override
    public void debugCollision(boolean b) {

        if (collisionGroup == null) {
            collisionGroup = new Group();

            for (Rectangle rectangle : collisionRect) {
                MapActor collisionActor = new MapActor(0);
                collisionActor.setBoundDebug(true);
                collisionActor.setWidth(rectangle.width);
                collisionActor.setHeight(rectangle.height);
                collisionActor.setX(rectangle.x);
                collisionActor.setY(rectangle.y);
                collisionGroup.addActor(collisionActor);
            }

        }
        if (b) {
            addActor(collisionGroup);
        } else {
            collisionGroup.remove();
        }
        super.debugCollision(b);
    }

    private void effectDialog(EffectData effectData) {
        dialog.getButtonTable().clear();
        dialog.getContentTable().clear();
        dialog.clearListeners();
        TextraButton ok = Controls.newTextButton("OK", this::hideDialog);
        ok.setVisible(false);
        TypingLabel L = Controls.newTypingLabel("{GRADIENT=CYAN;WHITE;1;1}Strange magical energies flow within this place...{ENDGRADIENT}\nAll opponents get:\n" + effectData.getDescription());
        L.setWrap(true);
        L.setTypingListener(new TypingAdapter() {
            @Override
            public void end() {
                ok.setVisible(true);
            }
        });
        dialog.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                L.skipToTheEnd();
                super.clicked(event, x, y);
            }
        });
        dialog.getButtonTable().add(ok).width(240f);
        dialog.getContentTable().add(L).width(250f);
        dialog.setKeepWithinStage(true);
        showDialog();
    }

    public void showImageDialog(String message, Texture texture) {
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();
        if (texture != null) {
            TextureRegion tr = new TextureRegion(texture);
            tr.flip(true, true);
            Image image = new Image(tr);
            image.setScaling(Scaling.fit);
            dialog.getContentTable().add(image).height(100);
            dialog.getContentTable().add().row();
        }
        TypingLabel L = Controls.newTypingLabel(message);
        L.setWrap(true);
        L.skipToTheEnd();
        dialog.getContentTable().add(L).width(250f);
        dialog.getButtonTable().add(Controls.newTextButton("OK", this::hideDialog)).width(240f);
        dialog.setKeepWithinStage(true);
        setDialogStage(GameHUD.getInstance());
        showDialog();
    }

    public void showDeckAwardDialog(String message, Deck deck) {
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();
        DeckProxy dp = new DeckProxy(deck, "Constructed", GameType.Constructed, null);
        FImageComplex cardArt = CardRenderer.getCardArt(dp.getHighestCMCCard());
        if (cardArt != null) {
            Image art = new Image(cardArt.getTextureRegion());
            art.setWidth(58);
            art.setHeight(46);
            art.setPosition(25, 43);
            Image image = new Image(FSkinImage.ADV_DECKBOX.getTextureRegion());
            image.setWidth(60);
            image.setHeight(80);
            image.setPosition(24, 10);
            ColorSet colorSet = DeckProxy.getColorIdentity(deck);
            TypingLabel deckColors = Controls.newTypingLabel(Controls.colorIdToTypingString(colorSet, true).toUpperCase());
            deckColors.skipToTheEnd();
            deckColors.setAlignment(Align.center);
            deckColors.setPosition(14, 44);
            TextraLabel deckname = Controls.newTextraLabel(deck.getName());
            deckname.setAlignment(Align.center);
            deckname.setWrap(true);
            deckname.setWidth(80);
            deckname.setPosition(14, 28);
            Group group = new Group();
            group.addActor(art);
            group.addActor(image);
            group.addActor(deckColors);
            group.addActor(deckname);
            dialog.getContentTable().add(group).height(100).width(100).center();
            dialog.getContentTable().add().row();
        } else {
            TypingLabel label = Controls.newTypingLabel("[%125]"+Controls.colorIdToTypingString(DeckProxy.getColorIdentity(deck)).toUpperCase()+"\n[%]"+deck.getName());
            label.skipToTheEnd();
            label.setAlignment(Align.center);
            dialog.getContentTable().add(label).align(Align.center);
            dialog.getContentTable().add().row();
        }

        TypingLabel L = Controls.newTypingLabel(message);
        L.setWrap(true);
        L.skipToTheEnd();

        dialog.getContentTable().add(L).width(250);
        dialog.getButtonTable().add(Controls.newTextButton("OK", this::hideDialog)).width(240);
        dialog.setKeepWithinStage(true);
        setDialogStage(GameHUD.getInstance());
        showDialog();
    }

    Array<EntryActor> otherEntries = new Array<>();
    Array<EntryActor> spawnClassified = new Array<>();
    Array<EntryActor> sourceMapMatch = new Array<>();

    public void loadMap(TiledMap map, String sourceMap) {
        isLoadingMatch = false;
        isInMap = true;
        GameHUD.getInstance().showHideMap(false);
        this.map = map;
        for (MapActor actor : new Array.ArrayIterator<>(actors)) {
            actor.remove();
            foregroundSprites.removeActor(actor);
        }

        actors.clear();
        collisionRect.clear();

        if (collisionGroup != null)
            collisionGroup.remove();
        collisionGroup = null;

        float width = Float.parseFloat(map.getProperties().get("width").toString());
        float height = Float.parseFloat(map.getProperties().get("height").toString());
        float tileHeight = Float.parseFloat(map.getProperties().get("tileheight").toString());
        float tileWidth = Float.parseFloat(map.getProperties().get("tilewidth").toString());
        setBounds(width * tileWidth, height * tileHeight);
        //collision = new Array[(int) width][(int) height];

        //Load dungeon effects.
        MapProperties MP = map.getProperties();

        if (MP.get("dungeonEffect") != null && !MP.get("dungeonEffect").toString().isEmpty()) {
            effect = JSONStringLoader.parse(EffectData.class, map.getProperties().get("dungeonEffect").toString(), "");
            effectDialog(effect);
        }
        if (MP.get("respawnEnemies") != null && MP.get("respawnEnemies") instanceof Boolean && (Boolean) MP.get("respawnEnemies")) {
            respawnEnemies = true;
        } else {
            respawnEnemies = false;
        }
        if (MP.get("canFailDungeon") != null && MP.get("canFailDungeon") instanceof Boolean && (Boolean) MP.get("canFailDungeon")) {
            canFailDungeon = true;
        } else {
            canFailDungeon = false;
        }
        if (MP.get("preventEscape") != null) preventEscape = (boolean) MP.get("preventEscape");

        if (MP.get("music") != null && !MP.get("music").toString().isEmpty()) {
            //TODO: Add a way to play a music file directly without using a playlist.
        }

        getPlayerSprite().stop();
        spriteLayer = null;
        otherEntries.clear();
        spawnClassified.clear();
        sourceMapMatch.clear();
        enemies.clear();
        for (MapLayer layer : map.getLayers()) {
            if (layer.getProperties().containsKey("spriteLayer") && layer.getProperties().get("spriteLayer", boolean.class)) {
                spriteLayer = layer;
            }
            if (layer instanceof TiledMapTileLayer) {
                loadCollision((TiledMapTileLayer) layer);
            } else {
                loadObjects(layer, sourceMap);
            }
        }
        if (!spawnClassified.isEmpty())
            spawnClassified.first().spawn();
        else if (!sourceMapMatch.isEmpty())
            sourceMapMatch.first().spawn();
        else if (!otherEntries.isEmpty())
            otherEntries.first().spawn();

        //reduce geometry in collision rectangles
        int oldSize;
        do {
            oldSize = collisionRect.size;
            for (int i = 0; i < collisionRect.size; i++) {
                Rectangle r1 = collisionRect.get(i);
                for (int j = i + 1; j < collisionRect.size; j++) {
                    Rectangle r2 = collisionRect.get(j);
                    if ((Math.abs(r1.x - (r2.x + r2.width)) < 1 && Math.abs(r1.y - r2.y) < 1 && Math.abs(r1.height - r2.height) < 1)//left edge is the same as right edge

                            || (Math.abs((r1.x + r1.width) - r2.x) < 1 && Math.abs(r1.y - r2.y) < 1 && Math.abs(r1.height - r2.height) < 1)//right edge is the same as left edge

                            || (Math.abs(r1.x - r2.x) < 1 && Math.abs((r1.y + r1.height) - r2.y) < 1 && Math.abs(r1.width - r2.width) < 1)//top edge is the same as bottom edge

                            || (Math.abs(r1.x - r2.x) < 1 && Math.abs(r1.y - (r2.y + r2.height)) < 1 && Math.abs(r1.width - r2.width) < 1)//bottom edge is the same as left edge

                            || containsOrEquals(r1, r2) || containsOrEquals(r2, r1)
                    ) {
                        r1.merge(r2);
                        collisionRect.removeIndex(j);
                        i--;
                        break;
                    }
                }
            }
        } while (oldSize != collisionRect.size);
        if (spriteLayer == null) System.err.print("Warning: No spriteLayer present in map.\n");

        replaceWaypoints();

        getPlayerSprite().stop();
    }

    void replaceWaypoints(){
        for (EnemySprite enemy : enemies)
              {
            for (EnemySprite.MovementBehavior behavior : enemy.movementBehaviors){
                if (behavior.getDestination() > 0 && waypoints.containsKey(behavior.getDestination())){
                    behavior.setX(waypoints.get(behavior.getDestination()).x);
                    behavior.setY(waypoints.get(behavior.getDestination()).y);
                }
              }
        }
    }

    static public boolean containsOrEquals(Rectangle r1, Rectangle r2) {
        float xmi = r2.x;
        float xma = xmi + r2.width;
        float ymi = r2.y;
        float yma = ymi + r2.height;
        return xmi >= r1.x && xmi <= r1.x + r1.width && xma >= r1.x && xma <= r1.x + r1.width && ymi >= r1.y && ymi <= r1.y + r1.height && yma >= r1.y && yma <= r1.y + r1.height;
    }

    private void loadCollision(TiledMapTileLayer layer) {
        for (int x = 0; x < layer.getWidth(); x++) {
            for (int y = 0; y < layer.getHeight(); y++) {
                TiledMapTileLayer.Cell cell = layer.getCell(x, y);
                if (cell == null)
                    continue;
                for (MapObject collision : cell.getTile().getObjects()) {
                    if (collision instanceof RectangleMapObject) {
                        Rectangle r = ((RectangleMapObject) collision).getRectangle();
                        collisionRect.add(new Rectangle((Math.round(layer.getTileWidth() * x) + r.x), (Math.round(layer.getTileHeight() * y) + r.y), Math.round(r.width), Math.round(r.height)));
                    }
                }
            }
        }
    }

    private boolean canSpawn(MapProperties prop) {
        DifficultyData difficultyData = Current.player().getDifficulty();
        boolean spawnEasy = prop.get("spawn.Easy", Boolean.class);
        boolean spawnNorm = prop.get("spawn.Normal", Boolean.class);
        boolean spawnHard = prop.get("spawn.Hard", Boolean.class);
        if (difficultyData.spawnRank == 2 && !spawnHard) return false;
        if (difficultyData.spawnRank == 1 && !spawnNorm) return false;
        if (difficultyData.spawnRank == 0 && !spawnEasy) return false;
        return true;
    }
    private void loadObjects(MapLayer layer, String sourceMap) {
        player.setMoveModifier(2);
        Array<String> shopsAlreadyPresent = new Array<>();
        for (MapObject obj : layer.getObjects()) {
            MapProperties prop = obj.getProperties();
            String type = prop.get("type", String.class);
            if (type != null) {
                int id = prop.get("id", int.class);
                if (changes.isObjectDeleted(id))
                    continue;
                boolean hidden = !obj.isVisible(); //Check if the object is invisible.

                String rotatingShop = "";

                switch (type) {
                    case "collision":
                        float cX = Float.parseFloat(prop.get("x").toString());
                        float cY = Float.parseFloat(prop.get("y").toString());
                        float cW = Float.parseFloat(prop.get("width").toString());
                        float cH = Float.parseFloat(prop.get("height").toString());
                        collisionRect.add(new Rectangle(cX, cY, cW, cH));
                        break;
                    case "waypoint":
                        waypoints.put(id, new Vector2(Float.parseFloat(prop.get("x").toString()), Float.parseFloat(prop.get("y").toString())));
                        break;
                    case "entry":
                        float x = Float.parseFloat(prop.get("x").toString());
                        float y = Float.parseFloat(prop.get("y").toString());
                        float w = Float.parseFloat(prop.get("width").toString());
                        float h = Float.parseFloat(prop.get("height").toString());

                        String targetMap = prop.get("teleport").toString();
                        boolean spawnPlayerThere = (targetMap == null || targetMap.isEmpty() && sourceMap.isEmpty()) ||//if target is null and "from world"
                                !sourceMap.isEmpty() && targetMap.equals(sourceMap);

                        EntryActor entry = new EntryActor(this, id, prop.get("teleport").toString(), x, y, w, h, prop.get("direction").toString());
                        if ((prop.containsKey("spawn") && prop.get("spawn").toString().equals("true")) && spawnPlayerThere) {
                            spawnClassified.add(entry);
                        } else if (spawnPlayerThere) {
                            sourceMapMatch.add(entry);
                        } else {
                            otherEntries.add(entry);
                        }
                        addMapActor(obj, entry);
                        break;
                    case "reward":
                        if (!canSpawn(prop)) break;
                        Object R = prop.get("reward");
                        if (R != null && !R.toString().isEmpty()) {
                            Object S = prop.get("sprite");
                            String Sp;
                            Sp = "sprites/treasure.atlas";
                            if (S != null && !S.toString().isEmpty()) Sp = S.toString();
                            else
                                System.err.printf("No sprite defined for reward (ID:%s), defaulting to \"sprites/treasure.atlas\"", id);
                            RewardSprite RW = new RewardSprite(id, R.toString(), Sp);
                            RW.hidden = hidden;
                            addMapActor(obj, RW);
                        }
                        break;
                    case "enemy":
                        if (!canSpawn(prop)) break;
                        Object enemy = prop.get("enemy");
                        if (enemy != null && !enemy.toString().isEmpty()) {
                            EnemyData EN = WorldData.getEnemy(enemy.toString());
                            if (EN == null) {
                                System.err.printf("Enemy \"%s\" not found.", enemy);
                                break;
                            }
                            EnemySprite mob = new EnemySprite(id, EN);
                            Object dialogObject = prop.get("dialog"); //Check if the enemy has a dialogue attached to it.
                            if (dialogObject != null && !dialogObject.toString().isEmpty()) {
                                mob.dialog = new MapDialog(dialogObject.toString(), this, mob.getId());
                            }
                            dialogObject = prop.get("defeatDialog"); //Check if the enemy has a defeat dialogue attached to it.
                            if (dialogObject != null && !dialogObject.toString().isEmpty()) {
                                mob.defeatDialog = new MapDialog(dialogObject.toString(), this, mob.getId());
                            }
                            dialogObject = prop.get("name"); //Check for name override.
                            if (dialogObject != null && !dialogObject.toString().isEmpty()) {
                                mob.nameOverride = dialogObject.toString();
                            }
                            dialogObject = prop.get("effect"); //Check for special effects.
                            if (dialogObject != null && !dialogObject.toString().isEmpty()) {
                                mob.effect = JSONStringLoader.parse(EffectData.class, dialogObject.toString(), "");
                            }
                            dialogObject = prop.get("ignoreDungeonEffect"); //Check for special effects.
                            if (dialogObject != null && !dialogObject.toString().isEmpty()) {
                                mob.ignoreDungeonEffect = Boolean.parseBoolean(dialogObject.toString());
                            }
                            dialogObject = prop.get("reward"); //Check for additional rewards.
                            if (dialogObject != null && !dialogObject.toString().isEmpty()) {
                                mob.rewards = JSONStringLoader.parse(RewardData[].class, dialogObject.toString(), "[]");
                            }
                            if (prop.containsKey("threatRange")) //Check for threat range.
                            {
                                mob.threatRange = Float.parseFloat(prop.get("threatRange").toString());
                            }
                            if (prop.containsKey("fleeRange")) //Check for flee range.
                            {
                                mob.fleeRange = Float.parseFloat(prop.get("fleeRange").toString());
                            }
                            mob.hidden = hidden; //Evil.

                            dialogObject = prop.get("waypoints");
                            if (dialogObject != null && !dialogObject.toString().isEmpty()) {
                                mob.parseWaypoints(dialogObject.toString());
                            }

                            enemies.add(mob);
                            addMapActor(obj, mob);
                        }
                        break;
                    case "dummy": //Does nothing. Mostly obstacles to be removed by ID by switches or such.
                        TiledMapTileMapObject obj2 = (TiledMapTileMapObject) obj;
                        DummySprite D = new DummySprite(id, obj2.getTextureRegion(), this);
                        addMapActor(obj, D);
                        //TODO: Ability to toggle their solid state.
                        //TODO: Ability to move them (using a sequence such as "UULU" for up, up, left, up).
                        break;
                    case "inn":
                        addMapActor(obj, new OnCollide(() -> Forge.switchScene(InnScene.instance())));
                        break;
                    case "spellsmith":
                        addMapActor(obj, new OnCollide(() -> Forge.switchScene(SpellSmithScene.instance())));
                        break;
                    case "shardtrader":
                        MapActor shardTraderActor = new OnCollide(() -> Forge.switchScene(ShardTraderScene.instance()));
                        addMapActor(obj, shardTraderActor);
                        if (prop.containsKey("hasSign") && Boolean.parseBoolean(prop.get("hasSign").toString()) && prop.containsKey("signYOffset") && prop.containsKey("signXOffset")) {
                            try {
                                TextureSprite sprite = new TextureSprite(Config.instance().getAtlas(ShardTraderScene.spriteAtlas).createSprite(ShardTraderScene.sprite));
                                sprite.setX(shardTraderActor.getX() + Float.parseFloat(prop.get("signXOffset").toString()));
                                sprite.setY(shardTraderActor.getY() + Float.parseFloat(prop.get("signYOffset").toString()));
                                addMapActor(sprite);

                            } catch (Exception e) {
                                System.err.print("Can not create Texture for Shard Trader");
                            }
                        }
                        break;
                    case "arena":
                        addMapActor(obj, new OnCollide(() -> {
                            ArenaData arenaData = JSONStringLoader.parse(ArenaData.class, prop.get("arena").toString(), "");
                            ArenaScene.instance().loadArenaData(arenaData, WorldSave.getCurrentSave().getWorld().getRandom().nextLong());
                            Forge.switchScene(ArenaScene.instance());
                        }));
                        break;
                    case "exit":
                        addMapActor(obj, new OnCollide(MapStage.this::exitDungeon));
                        break;
                    case "dialog":
                        if (obj instanceof TiledMapTileMapObject) {
                            TiledMapTileMapObject tiledObj = (TiledMapTileMapObject) obj;
                            DialogActor dialog;
                            if (prop.containsKey("sprite"))
                                dialog = new DialogActor(this, id, prop.get("dialog").toString(), prop.get("sprite").toString());
                            else
                                dialog = new DialogActor(this, id, prop.get("dialog").toString(), tiledObj.getTextureRegion());
                            addMapActor(obj, dialog);
                        }
                        break;
                    case "quest":
                        DialogActor dialog;
                        if (prop.containsKey("questtype")){
                            TiledMapTileMapObject tiledObj = (TiledMapTileMapObject) obj;

                            String questOrigin = prop.containsKey("questtype") ? prop.get("questtype").toString() : "";

                            String placeholderText = "[" +
                                    "  {" +
                                    "    \"name\":\"Quest Offer\"," +
                                    "    \"text\":\"Please, help us!\\n((QUEST DESCRIPTION))\"," +
                                    "    \"condition\":[]," +
                                    "    \"options\":[" +
                                    "        { \"name\":\"No, I'm not ready yet.\nMaybe next snapshot.\" }," +
                                    "    ]" +
                                    "  }" +
                                    "]";

                            {
                                dialog = new DialogActor(this, id, placeholderText,tiledObj.getTextureRegion());
                            }
                            dialog.setVisible(false);
                            addMapActor(obj, dialog);
                        }
                        break;

                    case "Rotating":
                        String rotation = "";
                        if (prop.containsKey("rotation")) {
                            rotation = prop.get("rotation").toString();
                        }

                        Array<String> possibleShops = new Array<>(rotation.split(","));

                        if (possibleShops.size > 0){
                            long rotatingRandomSeed = WorldSave.getCurrentSave().getWorld().getRandom().nextLong() + LocalDate.now().toEpochDay();
                            Random rotatingShopRandom = new Random(rotatingRandomSeed);
                            rotatingShop = possibleShops.get(rotatingShopRandom.nextInt(possibleShops.size));
                            changes.setRotatingShopSeed(id, rotatingRandomSeed);
                        }

                    //Intentionally not breaking here.
                    //Flow continues into "shop" case with above data overriding base logic.

                    case "shop":

                        int restockPrice = 0;
                        String shopList = "";

                        boolean isRotatingShop = !rotatingShop.isEmpty();

                        if (isRotatingShop){
                            shopList = rotatingShop;
                            restockPrice = 7;
                        }
                        else{
                            int rarity = WorldSave.getCurrentSave().getWorld().getRandom().nextInt(100);
                            if (rarity > 95 & prop.containsKey("mythicShopList")) {
                                shopList = prop.get("mythicShopList").toString();
                                restockPrice = 5;
                            }
                            if (shopList.isEmpty() && (rarity > 85 & prop.containsKey("rareShopList"))) {
                                shopList = prop.get("rareShopList").toString();
                                restockPrice = 4;
                            }
                            if (shopList.isEmpty() && (rarity > 55 & prop.containsKey("uncommonShopList"))) {
                                shopList = prop.get("uncommonShopList").toString();
                                restockPrice = 3;
                            }
                            if (shopList.isEmpty() && prop.containsKey("commonShopList")) {
                                shopList = prop.get("commonShopList").toString();
                                restockPrice = 2;
                            }
                            if (shopList.trim().isEmpty() && prop.containsKey("shopList")) {
                                shopList = prop.get("shopList").toString(); //removed but included to not break existing custom planes
                                restockPrice = 0; //Tied to restock button
                            }
                            shopList = shopList.replaceAll("\\s", "");

                        }

                        if (prop.containsKey("noRestock") && (boolean)prop.get("noRestock")){
                            restockPrice = 0;
                        }

                        possibleShops = new Array<String>(shopList.split(","));
                        Array<String> filteredPossibleShops = new Array<>();
                        if (!isRotatingShop){
                            for (String candidate : possibleShops)
                            {
                                if (!shopsAlreadyPresent.contains(candidate, false))
                                    filteredPossibleShops.add(candidate);
                            }
                        }
                        if (filteredPossibleShops.isEmpty()){
                            filteredPossibleShops = possibleShops;
                        }
                        Array<ShopData> shops;
                        if (filteredPossibleShops.size == 0 || shopList.equals(""))
                            shops = WorldData.getShopList();
                        else {
                            shops = new Array<>();
                            for (ShopData data : new Array.ArrayIterator<>(WorldData.getShopList())) {
                                if (filteredPossibleShops.contains(data.name, false)) {
                                    data.restockPrice = restockPrice;
                                    shops.add(data);
                                }
                            }
                        }
                        if (shops.size == 0) continue;

                        ShopData data = shops.get(WorldSave.getCurrentSave().getWorld().getRandom().nextInt(shops.size));
                        shopsAlreadyPresent.add(data.name);
                        Array<Reward> ret = new Array<>();
                        WorldSave.getCurrentSave().getWorld().getRandom().setSeed(changes.getShopSeed(id));
                        for (RewardData rdata : new Array.ArrayIterator<>(data.rewards)) {
                            ret.addAll(rdata.generate(false));
                        }
                        ShopActor actor = new ShopActor(this, id, ret, data);
                        addMapActor(obj, actor);
                        if (prop.containsKey("hasSign") && (boolean)prop.get("hasSign") && prop.containsKey("signYOffset") && prop.containsKey("signXOffset")) {
                            try {
                                TextureSprite sprite = new TextureSprite(Config.instance().getAtlas(data.spriteAtlas).createSprite(data.sprite));
                                sprite.setX(actor.getX() + Float.parseFloat(prop.get("signXOffset").toString()));
                                sprite.setY(actor.getY() + Float.parseFloat(prop.get("signYOffset").toString()));
                                addMapActor(sprite);

                                if (!(data.overlaySprite == null | data.overlaySprite.isEmpty())){
                                    TextureSprite overlay = new TextureSprite(Config.instance().getAtlas(data.spriteAtlas).createSprite(data.overlaySprite));
                                    overlay.setX(actor.getX() + Float.parseFloat(prop.get("signXOffset").toString()));
                                    overlay.setY(actor.getY() + Float.parseFloat(prop.get("signYOffset").toString()));
                                    addMapActor(overlay);
                                }
                            } catch (Exception e) {
                                System.err.print("Can not create Texture for " + data.sprite + " Obj:" + data);
                            }
                        }
                        break;
                    default:
                        System.err.println("Unexpected value: " + type);
                }
            }
        }
    }

    public boolean exitDungeon() {
        isLoadingMatch = false;
        effect = null; //Reset dungeon effects.
        clearIsInMap();
        Forge.switchScene(GameScene.instance());
        return true;
    }


    @Override
    public void setWinner(boolean playerWins) {
        isLoadingMatch = false;
        if (playerWins) {

            Current.player().win();
            player.setAnimation(CharacterSprite.AnimationTypes.Attack);
            currentMob.playEffect(Paths.EFFECT_BLOOD, 0.5f);
            Timer.schedule(new Timer.Task() {
                @Override
                public void run() {
                    currentMob.setAnimation(CharacterSprite.AnimationTypes.Death);
                    startPause(0.3f, MapStage.this::getReward);
                }
            }, 1f);
        } else {
            player.setAnimation(CharacterSprite.AnimationTypes.Hit);
            currentMob.setAnimation(CharacterSprite.AnimationTypes.Attack);

            startPause(0.3f, () -> {
                player.setAnimation(CharacterSprite.AnimationTypes.Idle);
                currentMob.setAnimation(CharacterSprite.AnimationTypes.Idle);
                player.setPosition(oldPosition4);
                currentMob.freezeMovement();
                boolean defeated = Current.player().defeated();
                if (canFailDungeon && defeated)
                {
                    //If hardcore mode is added, check and redirect to game over screen here
                    dungeonFailedDialog();
                    exitDungeon();
                }
                MapStage.this.stop();
                currentMob = null;
            });
        }
    }

    private void dungeonFailedDialog() {
        dialog.getButtonTable().clear();
        dialog.getContentTable().clear();
        dialog.clearListeners();
        TextraButton ok = Controls.newTextButton("OK", this::hideDialog);
        ok.setVisible(false);
        TypingLabel L = Controls.newTypingLabel("{GRADIENT=RED;WHITE;1;1}Defeated and unable to continue, you use the last of your power to escape the area.");
        L.setWrap(true);
        L.setTypingListener(new TypingAdapter() {
            @Override
            public void end() {
                ok.setVisible(true);
            }
        });
        dialog.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                L.skipToTheEnd();
                super.clicked(event, x, y);
                //exitDungeon();
            }
        });
        dialog.getButtonTable().add(ok).width(240f);
        dialog.getContentTable().add(L).width(250f);
        dialog.setKeepWithinStage(true);
        showDialog();
    }

    public boolean deleteObject(int id) {
        changes.deleteObject(id);
        for (int i = 0; i < actors.size; i++) {
            if (actors.get(i).getObjectId() == id && id > 0) {
                actors.get(i).remove();
                actors.removeIndex(i);
                return true;
            }
        }
        return false;
    }

    public boolean lookForID(int id) { //Search actor by ID.

        for (MapActor A : new Array.ArrayIterator<>(actors)) {
            if (A.getId() == id)
                return true;
        }
        return false;
    }

    public EnemySprite getEnemyByID(int id) { //Search actor by ID, enemies only.
        for (MapActor A : new Array.ArrayIterator<>(actors)) {
            if (A instanceof EnemySprite && A.getId() == id)
                return ((EnemySprite) A);
        }
        return null;
    }

    protected void getReward() {
        isLoadingMatch = false;
        RewardScene.instance().loadRewards(currentMob.getRewards(), RewardScene.Type.Loot, null);
        Forge.switchScene(RewardScene.instance());
        if (currentMob.defeatDialog == null) {
            currentMob.remove();
            actors.removeValue(currentMob, true);
            if (!respawnEnemies || currentMob.getData().boss)
                changes.deleteObject(currentMob.getId());
        } else {
            currentMob.defeatDialog.activate();
            player.setAnimation(CharacterSprite.AnimationTypes.Idle);
            currentMob.setAnimation(CharacterSprite.AnimationTypes.Idle);
        }
        currentMob = null;
    }

    public void removeAllEnemies() {
        Array<Integer> idsToRemove = new Array<>();
        for (MapActor actor : new Array.ArrayIterator<>(actors)) {
            if (actor instanceof EnemySprite) {
                idsToRemove.add(actor.getObjectId());
            }
        }
        for (Integer i : idsToRemove) deleteObject(i);
    }

    @Override
    protected void onActing(float delta) {
        Iterator<EnemySprite> it = enemies.iterator();
        while (it.hasNext()) {
            EnemySprite mob = it.next();
            mob.updatePositon();
            mob.targetVector = mob.getTargetVector(player, delta);
            Vector2 currentVector = new Vector2(mob.targetVector);
            mob.clearActions();
            if (mob.targetVector.len() == 0.0f) {
                mob.setAnimation(CharacterSprite.AnimationTypes.Idle);
                continue;
            }

            if (!mob.getData().flying)//if direct path is not possible
            {
                //Todo: fix below for collision logic
                float safeLen = lengthWithoutCollision(mob, mob.targetVector);
                if (safeLen > 0.1f) {
                    currentVector.setLength(Math.min(safeLen, mob.targetVector.len()));
                }
                else {
                    currentVector = Vector2.Zero;
                }
            }


            //mob.targetVector.setLength(Math.min(mob.speed() * delta, mob.targetVector.len()));
//            if (mob.targetVector.len() < 0.3f) {
//                mob.targetVector = Vector2.Zero;
//            }
            currentVector.setLength(Math.min(mob.speed() * delta, mob.targetVector.len()));
            //if (destination.len() < 0.3f) destination = Vector2.Zero;
            mob.moveBy(currentVector.x, currentVector.y);

        }


        float sprintingMod = currentModifications.containsKey(PlayerModification.Sprint) ? 2 : 1;
        player.setMoveModifier(2 * sprintingMod);
        oldPosition4.set(oldPosition3);
        oldPosition3.set(oldPosition2);
        oldPosition2.set(oldPosition);
        oldPosition.set(player.pos());
        for (MapActor actor : new Array.ArrayIterator<>(actors)) {
            if (actor.collideWithPlayer(player)) {
                if (actor instanceof EnemySprite) {
                    EnemySprite mob = (EnemySprite) actor;
                    currentMob = mob;
                    resetPosition();
                    if (mob.dialog != null && mob.dialog.canShow()) { //This enemy has something to say. Display a dialog like if it was a DialogActor but only if dialogue is possible.
                        mob.dialog.activate();
                    } else { //Duel the enemy.
                        beginDuel(mob);
                    }
                    break;
                } else if (actor instanceof RewardSprite) {
                    Gdx.input.vibrate(50);
                    if (Controllers.getCurrent() != null && Controllers.getCurrent().canVibrate())
                        Controllers.getCurrent().startVibration(100, 1);
                    startPause(0.1f, () -> { //Switch to item pickup scene.
                        RewardSprite RS = (RewardSprite) actor;
                        RewardScene.instance().loadRewards(RS.getRewards(), RewardScene.Type.Loot, null);
                        RS.remove();
                        actors.removeValue(RS, true);
                        changes.deleteObject(RS.getId());
                        Forge.switchScene(RewardScene.instance());
                    });
                    break;
                }
            }
        }
    }

    public void beginDuel(EnemySprite mob) {
        if (mob == null) return;
        currentMob = mob;
        player.setAnimation(CharacterSprite.AnimationTypes.Attack);
        player.playEffect(Paths.EFFECT_SPARKS, 0.5f);
        mob.setAnimation(CharacterSprite.AnimationTypes.Attack);
        SoundSystem.instance.play(SoundEffectType.Block, false);
        Gdx.input.vibrate(50);
        int duration = mob.getData().boss ? 400 : 200;
        if (Controllers.getCurrent() != null && Controllers.getCurrent().canVibrate())
            Controllers.getCurrent().startVibration(duration, 1);
        startPause(0.8f, () -> {
            Forge.setCursor(null, Forge.magnifyToggle ? "1" : "2");
            SoundSystem.instance.play(SoundEffectType.ManaBurn, false);
            DuelScene duelScene = DuelScene.instance();
            FThreads.invokeInEdtNowOrLater(() -> {
                if (!isLoadingMatch) {
                    isLoadingMatch = true;
                    Forge.setTransitionScreen(new TransitionScreen(() -> {
                        duelScene.initDuels(player, mob);
                        if (isInMap && effect != null && !mob.ignoreDungeonEffect)
                            duelScene.setDungeonEffect(effect);
                        Forge.switchScene(duelScene);
                    }, Forge.takeScreenshot(), true, false, false, false, "", Current.player().avatar(), mob.getAtlasPath(), Current.player().getName(), mob.nameOverride.isEmpty() ? mob.getData().name : mob.nameOverride));
                }
            });
        });
    }

    public void setPointOfInterest(PointOfInterestChanges change) {
        changes = change;
    }

    public boolean isInMap() {
        return isInMap;
    }

    public boolean isDialogOnlyInput() {
        return dialogOnlyInput;
    }

    public void showDialog() {

        dialogButtonMap.clear();
        for (int i = 0; i < dialog.getButtonTable().getCells().size; i++) {
            dialogButtonMap.add((TextraButton) dialog.getButtonTable().getCells().get(i).getActor());
        }
        dialog.show(dialogStage, Actions.show());
        dialog.setPosition((dialogStage.getWidth() - dialog.getWidth()) / 2, (dialogStage.getHeight() - dialog.getHeight()) / 2);
        dialogOnlyInput = true;
        if (Forge.hasGamepad() && !dialogButtonMap.isEmpty())
            dialogStage.setKeyboardFocus(dialogButtonMap.first());
    }

    public void hideDialog() {
        dialog.hide(Actions.sequence(Actions.sizeTo(dialog.getOriginX(), dialog.getOriginY(), 0.3f), Actions.hide()));
        dialogOnlyInput = false;
        selectedKey = null;
    }

    public void setDialogStage(Stage dialogStage) {
        this.dialogStage = dialogStage;
    }

    public void resetPosition() {
        player.setPosition(oldPosition4);
        stop();
    }

    public void setQuestFlag(String key, int value) {
        changes.getMapFlags().put(key, (byte) value);
    }

    public void advanceQuestFlag(String key) {
        Map<String, Byte> C = changes.getMapFlags();
        if (C.get(key) != null) {
            C.put(key, (byte) (C.get(key) + 1));
        } else {
            C.put(key, (byte) 1);
        }
    }

    public boolean checkQuestFlag(String key) {
        return changes.getMapFlags().get(key) != null;
    }

    public int getQuestFlag(String key) {
        return (int) changes.getMapFlags().getOrDefault(key, (byte) 0);
    }

    public void resetQuestFlags() {
        changes.getMapFlags().clear();
    }

    public boolean dialogInput(int keycode) {
        if (dialogOnlyInput) {
            if (KeyBinding.Up.isPressed(keycode)) {
                selectPreviousDialogButton();
            }
            if (KeyBinding.Down.isPressed(keycode)) {
                selectNextDialogButton();
            }
            if (KeyBinding.Use.isPressed(keycode)) {
                performTouch(dialogStage.getKeyboardFocus());
            }
        }
        return true;
    }

    public void performTouch(Actor actor) {
        if (actor == null)
            return;
        actor.fire(eventTouchDown);
        Timer.schedule(new Timer.Task() {
            @Override
            public void run() {
                actor.fire(eventTouchUp);
            }
        }, 0.10f);
    }

    private void selectNextDialogButton() {
        if (dialogButtonMap.size < 2)
            return;
        if (!(dialogStage.getKeyboardFocus() instanceof Button)) {
            dialogStage.setKeyboardFocus(dialogButtonMap.first());
            return;
        }
        for (int i = 0; i < dialogButtonMap.size; i++) {
            if (dialogStage.getKeyboardFocus() == dialogButtonMap.get(i)) {
                i += 1;
                i %= dialogButtonMap.size;
                dialogStage.setKeyboardFocus(dialogButtonMap.get(i));
                return;
            }
        }
    }

    private void selectPreviousDialogButton() {
        if (dialogButtonMap.size < 2)
            return;
        if (!(dialogStage.getKeyboardFocus() instanceof Button)) {
            dialogStage.setKeyboardFocus(dialogButtonMap.first());
            return;
        }
        for (int i = 0; i < dialogButtonMap.size; i++) {
            if (dialogStage.getKeyboardFocus() == dialogButtonMap.get(i)) {
                i -= 1;
                if (i < 0)
                    i = dialogButtonMap.size - 1;
                dialogStage.setKeyboardFocus(dialogButtonMap.get(i));
                return;
            }
        }
    }
}
