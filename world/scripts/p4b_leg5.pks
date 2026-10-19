# Expansion E2, leg 5 (Gym 4 -> Gym 5): Prism Grotto, the Eclipse Dig Site, Mistwood.

# --- Prism Grotto: the crystal hermit and his shards -----------------------------------
script p4b_hermit
  faceplayer
  if quest p4b_prism_choir done
    say "HERMIT: Hear it? The grotto sings in seven voices again. You did that."
  elif flag p4b_shard1 and flag p4b_shard2 and flag p4b_shard3
    say "HERMIT: All three shards! Hold them up to the light... there.\pThe grotto's song was missing three notes. Now the choir is whole. Take these, with an old man's thanks."
    if not quest p4b_prism_choir started
      quest p4b_prism_choir start
    end
    quest p4b_prism_choir done
    tm powergem
    giveitem luckyegg
    say "HERMIT: Power Gem fires a beam of crystal light. And the Lucky Egg... it helps young Pokémon learn faster. Like you."
  else
    say "HERMIT: Shh. Listen. The crystals of this grotto sing, each in its own color.\pThree of them have fallen silent. Their shards broke loose and lie scattered around the lake.\pBring me the three prism shards, and I'll teach you a song of my own."
    if not quest p4b_prism_choir started
      quest p4b_prism_choir start
    end
  end
end

script p4b_shard1
  say "A prism shard, faintly humming. It glows a soft violet."
  setflag p4b_shard1
  hide p4b_shard1
  call p4b_shard_count
end

script p4b_shard2
  say "A prism shard, faintly humming. It glows a deep indigo."
  setflag p4b_shard2
  hide p4b_shard2
  call p4b_shard_count
end

script p4b_shard3
  say "A prism shard, faintly humming. It glows a bright crimson."
  setflag p4b_shard3
  hide p4b_shard3
  call p4b_shard_count
end

script p4b_shard_count
  if flag p4b_shard1 and flag p4b_shard2 and flag p4b_shard3
    say "That's all three shards. The hermit on the central island is waiting."
    if quest p4b_prism_choir started
      quest p4b_prism_choir next
    end
  else
    say "{player} pocketed the shard. There should be more around the lake."
  end
end

# --- Eclipse Dig Site -------------------------------------------------------------------
script p4b_maeve
  faceplayer
  if quest p4b_stolen_light done
    say "MAEVE: The crystal heart is safe, and Carbink's home. I'll report the dig to the Quarry Guild and Warden Talo."
  elif flag p4b_dig_cleared
    say "MAEVE: You beat their scientist? The siphon's dead quiet! Tessera owes you, kid. So do I."
    giveitem expcandyl
    giveitem maxrevive
    quest p4b_stolen_light done
    say "MAEVE: Exp. Candy and a Max Revive. Guild survey kit. Don't tell my boss."
  elif quest p4b_stolen_light started
    say "MAEVE: Their siphon is at the far end of the dig. Take the west tunnel south, or shift the boulder in their camp."
  else
    say "MAEVE: Shh! Get down! I'm Maeve, a Quarry Guild surveyor. I followed Team Eclipse down this ladder.\pThey've found the crystal heart of Mt. Prism and they're siphoning its light. They even caged a Carbink to test the machine.\pI'm no battler. Could you stop their scientist?"
    quest p4b_stolen_light start
  end
end

script p4b_dig_scientist
  faceplayer
  say "NYLE: Do you know what you're looking at? Mt. Prism's crystal heart, the light of a fallen star!\pLord Umbra will swallow it whole. Now go away before you jostle my readings!"
  battle p4b_dig_sci_nyle
  if result win
    say "NYLE: My readings... my calibration... Hmph. We have enough light for now. Grunts! Pack up!"
    setflag p4b_dig_cleared
    if quest p4b_stolen_light started
      quest p4b_stolen_light next
    end
    fadeout
    hide p4b_dig_sci
    fadein
  end
end

script p4b_dig_carbink
  if flag p4b_dig_cleared
    say "The cage door hangs open. The Carbink inside sparkles and hops toward {player}!"
    givemon carbink 36 ability=H
    if result yes
      setflag p4b_got_carbink
      hide p4b_dig_carbink
      say "Carbink seems to want to come along!"
    else
      say "Your party and your boxes are full. Make some room, then come back for it."
    end
  else
    say "A Carbink, trapped in a humming cage wired to the siphon. It's too weak to sparkle."
  end
end

# --- Seraphine Town / Mistwood: Lost in the Mist --------------------------------------
script p4b_rowan
  faceplayer
  if quest p4b_lost_poppy done
    say "MRS. ROWAN: Poppy won't stop talking about the Clefairy she saw. I suppose we'll be going back. Together."
  elif flag p4b_poppy_found
    say "MRS. ROWAN: Poppy came running out of the fog, shouting about a trainer who found her! Thank you, thank you.\pMy late husband's TM. He'd want it to go to someone brave."
    tm playrough
    quest p4b_lost_poppy done
    say "MRS. ROWAN: Play Rough. A Fairy-type move that hits hard. Elowen herself uses it."
  else
    say "MRS. ROWAN: My daughter Poppy chased a Clefairy into Mistwood and hasn't come back!\pThe fog's so thick, and the paths wander... Please, if you're going north, look for her."
    if not quest p4b_lost_poppy started
      quest p4b_lost_poppy start
    end
  end
end

script p4b_poppy
  faceplayer
  say "POPPY: Sniff... I followed a Clefairy and now I can't see the way home...\pYou know the way? The path goes south past the moon pond? Okay! I'm not scared anymore!"
  setflag p4b_poppy_found
  if quest p4b_lost_poppy started
    quest p4b_lost_poppy next
  else
    quest p4b_lost_poppy start
    quest p4b_lost_poppy next
  end
  fadeout
  hide p4b_poppy
  fadein
  say "Poppy ran off toward Seraphine Town!"
end

script p4b_moon_shrine
  if flag p4b_moon_clefairy
    say "A little stone shrine to the moon. The grass around it is pressed flat by tiny dancing feet."
  elif time night
    say "Under the moonlight, a circle of Clefairy dances around the shrine! One of them notices {player}..."
    wildbattle clefairy 36
    if result caught or result defeated
      setflag p4b_moon_clefairy
      say "The other Clefairy scattered into the fog."
    end
  else
    say "A little stone shrine to the moon, worn smooth by time. The engraving reads: \"When the moon is high, the pink ones dance.\""
  end
end

script p4b_hollow_mime
  say "A Mr. Mime is pressing its hands against an invisible wall... It turns and bows!"
  wildbattle mrmime 37
  if result caught or result defeated
    setflag p4b_mime_done
    hide p4b_hollow_mime
  else
    say "Mr. Mime went back to miming its wall."
  end
end

script p4b_ranger_elm
  faceplayer
  say "ELM: Lost in the fog? Everyone is, their first time. Rest here a while."
  heal
  say "ELM: Mistwood's fog comes off the moon pond. Elowen says it keeps the fairies safe from harsh light. Team Eclipse seems to like the idea a little too much."
end

script p4b_trade_ivo
  faceplayer
  if flag p4b_traded_impidimp
    say "IVO: My Hatenna is the calmest Pokémon I've ever met. And Puck? Causing trouble for you now, I'd wager."
  else
    say "IVO: My Impidimp, Puck, keeps tying the ranger's bootlaces together. He needs a trainer with more energy than me."
    ask "Would you trade a calm Hatenna for Puck?"
    if result yes
      trade hatenna impidimp 35 nick=Puck ot=IVO
      if result 1
        setflag p4b_traded_impidimp
        say "IVO: Splendid! Puck, behave. ...He won't, of course."
      else
        say "IVO: No Hatenna? They nap in the grass around Seraphine Town."
      end
    else
      say "IVO: Hatenna live around Seraphine Town, should you change your mind."
    end
  end
end

# --- Mistwood Depths: the fairy shrine ----------------------------------------------
script p4b_fairy_shrine
  say "A mossy shrine shaped like a sleeping fairy. Someone leaves fresh flowers here every day.\pThe inscription reads: \"Gentle light, hidden light. The mist keeps what the sun would burn.\""
end

script p4b_granny_moss
  faceplayer
  if flag p4b_moss_gift
    say "GRANNY MOSS: Elowen still visits on rainy days. Tell her Granny Moss says her tea is getting cold."
  elif badge 5
    say "GRANNY MOSS: Elowen gave you her badge? Then she saw what I see. A gentle strength.\pThis was hers, when she was a little girl practicing here. She'd want you to have it."
    giveitem pixieplate
    setflag p4b_moss_gift
    say "GRANNY MOSS: A Pixie Plate. It makes Fairy moves shine brighter. Just like you."
  else
    say "GRANNY MOSS: Well, now. A visitor who found the shrine through the fog.\pI taught a little girl named Elowen here, long ago. Now she leads the Gym in town.\pShe hides her strength in the mist, as I taught her. Show her yours, and come back to tell me about it."
  end
end

# --- Seraphine Mart: Sweets for Milcery -------------------------------------------------
script p4b_patissier
  faceplayer
  say "PÂTISSIER MILLE: Bonjour! I bake Sweets for the Milcery of the Mist Grove.\pHere's a secret: let a Milcery hold a Sweet, then spin around on the spot with it, four quick turns! It becomes an Alcremie!\pEach Sweet gives a different decoration. Care to buy one?"
  shop p4b_sweets
  say "PÂTISSIER MILLE: Spin clockwise, spin counter-clockwise, spin by day or by night... Every Alcremie is a little different!"
end
