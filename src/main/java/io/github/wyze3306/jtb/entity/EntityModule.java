package io.github.wyze3306.jtb.entity;

import io.github.wyze3306.jtb.translator.TranslatorModule;
import io.github.wyze3306.jtb.translator.TranslatorRegistry;
import org.cloudburstmc.protocol.bedrock.packet.ActorEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddActorPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddItemActorPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPaintingPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddVolumeEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AnimateEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AnimatePacket;
import org.cloudburstmc.protocol.bedrock.packet.AvailableActorIdentifiersPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChangeMobPropertyPacket;
import org.cloudburstmc.protocol.bedrock.packet.EmoteListPacket;
import org.cloudburstmc.protocol.bedrock.packet.EmotePacket;
import org.cloudburstmc.protocol.bedrock.packet.MobArmorEquipmentPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket;
import org.cloudburstmc.protocol.bedrock.packet.MotionPredictionHintsPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveActorAbsolutePacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveActorDeltaPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerSkinPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveActorPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveVolumeEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetActorDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetActorLinkPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetActorMotionPacket;
import org.cloudburstmc.protocol.bedrock.packet.SpawnExperienceOrbPacket;
import org.cloudburstmc.protocol.bedrock.packet.SyncActorPropertyPacket;
import org.cloudburstmc.protocol.bedrock.packet.TakeItemActorPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;

/**
 * Translators of the entity subsystem. See docs/DEVELOPING.md for the packets it owns. Loads the
 * static entity mappings ({@link EntityDefinitions}) when the registry is built at startup.
 */
public final class EntityModule implements TranslatorModule {

    @Override
    public void register(TranslatorRegistry registry) {
        EntityDefinitions.load();

        registry.bedrock(AddActorPacket.class, SpawnTranslators::addActor);
        registry.bedrock(AddPlayerPacket.class, SpawnTranslators::addPlayer);
        registry.bedrock(AddItemActorPacket.class, SpawnTranslators::addItemActor);
        registry.bedrock(AddPaintingPacket.class, SpawnTranslators::addPainting);
        registry.bedrock(SpawnExperienceOrbPacket.class, SpawnTranslators::spawnExperienceOrb);
        registry.bedrock(RemoveActorPacket.class, SpawnTranslators::removeActor);
        registry.bedrock(TakeItemActorPacket.class, SpawnTranslators::takeItemActor);

        registry.bedrock(MoveActorAbsolutePacket.class, MovementTranslators::moveActorAbsolute);
        registry.bedrock(MoveActorDeltaPacket.class, MovementTranslators::moveActorDelta);
        registry.bedrock(SetActorMotionPacket.class, MovementTranslators::setActorMotion);
        registry.bedrock(MotionPredictionHintsPacket.class, MovementTranslators::motionPredictionHints);

        registry.bedrock(SetActorDataPacket.class, DataTranslators::setActorData);
        registry.bedrock(SetActorLinkPacket.class, DataTranslators::setActorLink);
        registry.bedrock(PlayerListPacket.class, DataTranslators::playerList);
        registry.bedrock(SyncActorPropertyPacket.class, DataTranslators::syncActorProperty);
        registry.bedrock(AvailableActorIdentifiersPacket.class, DataTranslators::availableActorIdentifiers);
        registry.bedrock(ChangeMobPropertyPacket.class, DataTranslators::changeMobProperty);

        registry.bedrock(UpdateAttributesPacket.class, AttributeTranslators::updateAttributes);
        registry.bedrock(MobEffectPacket.class, AttributeTranslators::mobEffect);

        registry.bedrock(ActorEventPacket.class, EventTranslators::actorEvent);
        registry.bedrock(AnimatePacket.class, EventTranslators::animate);
        registry.bedrock(AnimateEntityPacket.class, EventTranslators::animateEntity);
        registry.bedrock(EmotePacket.class, EventTranslators::emote);

        registry.bedrock(MobEquipmentPacket.class, EquipmentTranslators::mobEquipment);
        registry.bedrock(MobArmorEquipmentPacket.class, EquipmentTranslators::mobArmorEquipment);

        // Nothing a vanilla Java client can show: Bedrock skins (Java renders only Mojang-signed ones),
        // emote piece lists, and volume entities (client-side fog/trigger volumes).
        registry.bedrock(PlayerSkinPacket.class, (session, packet) -> { });
        registry.bedrock(EmoteListPacket.class, (session, packet) -> { });
        registry.bedrock(AddVolumeEntityPacket.class, (session, packet) -> { });
        registry.bedrock(RemoveVolumeEntityPacket.class, (session, packet) -> { });
    }
}
