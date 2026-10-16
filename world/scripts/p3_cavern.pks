# Route 10, Crystal Cavern (Quartz, Tera Orb, Tera Shard exchange), Route 11 (Lapras gift).

script r10_cavern_guard
  faceplayer
  say "QUARTZ'S AIDE: Sorry! The cavern is unstable, so Quartz only lets in trainers with the Ember Badge.\pBlaise's Gym is back in Ashfall City."
end

script cc_quartz
  faceplayer
  if not flag p3_got_teraorb
    if trainer p3_cc_grunt_c and trainer p3_cc_grunt_d
      say "QUARTZ: You drove off those Eclipse goons! They wanted my crystals to power some machine on Mt. Prism.\pI'm Quartz. I study Tera crystals, the gems that let Pokémon shine with a new type."
      say "QUARTZ: Blaise told me about you. Here. This orb is charged with Tera energy."
      giveitem teraorb
      unlock tera
      setflag p3_got_teraorb
      say "QUARTZ: Once per battle, your Pokémon can Terastallize and become its Tera Type! The orb recharges at any Pokémon Center.\pBring me 3 Tera Shards of a type and I'll change a Pokémon's Tera Type."
      say "QUARTZ: Those grunts fled west. The tunnel there leads to Route 11 and the sea. Frostmere is across the bay."
      quest p3_story next
    else
      say "QUARTZ: Ah! A trainer! Those Team Eclipse grunts by the west tunnel are trying to haul away my crystals.\pPlease, chase them off!"
    end
  else
    call cc_tera_exchange
  end
end

script cc_tera_exchange
  ask "QUARTZ: Want to change a Pokémon's Tera Type? It costs 3 Tera Shards of the new type."
  if result yes
    choice "Which type?" "Fire" "Water" "Grass" "Electric" "Ice" "Fighting" "Poison" "Ground" "Flying" "Psychic" "Bug" "Rock" "Ghost" "Dragon" "Dark" "Steel" "Fairy" "Normal"
    if result 0 and item fireterashard >= 3
      takeitem fireterashard 3
      special set_tera_type Fire
    elif result 1 and item waterterashard >= 3
      takeitem waterterashard 3
      special set_tera_type Water
    elif result 2 and item grassterashard >= 3
      takeitem grassterashard 3
      special set_tera_type Grass
    elif result 3 and item electricterashard >= 3
      takeitem electricterashard 3
      special set_tera_type Electric
    elif result 4 and item iceterashard >= 3
      takeitem iceterashard 3
      special set_tera_type Ice
    elif result 5 and item fightingterashard >= 3
      takeitem fightingterashard 3
      special set_tera_type Fighting
    elif result 6 and item poisonterashard >= 3
      takeitem poisonterashard 3
      special set_tera_type Poison
    elif result 7 and item groundterashard >= 3
      takeitem groundterashard 3
      special set_tera_type Ground
    elif result 8 and item flyingterashard >= 3
      takeitem flyingterashard 3
      special set_tera_type Flying
    elif result 9 and item psychicterashard >= 3
      takeitem psychicterashard 3
      special set_tera_type Psychic
    elif result 10 and item bugterashard >= 3
      takeitem bugterashard 3
      special set_tera_type Bug
    elif result 11 and item rockterashard >= 3
      takeitem rockterashard 3
      special set_tera_type Rock
    elif result 12 and item ghostterashard >= 3
      takeitem ghostterashard 3
      special set_tera_type Ghost
    elif result 13 and item dragonterashard >= 3
      takeitem dragonterashard 3
      special set_tera_type Dragon
    elif result 14 and item darkterashard >= 3
      takeitem darkterashard 3
      special set_tera_type Dark
    elif result 15 and item steelterashard >= 3
      takeitem steelterashard 3
      special set_tera_type Steel
    elif result 16 and item fairyterashard >= 3
      takeitem fairyterashard 3
      special set_tera_type Fairy
    elif result 17 and item normalterashard >= 3
      takeitem normalterashard 3
      special set_tera_type Normal
    else
      say "QUARTZ: Hmm, you don't have 3 shards of that type. They glitter in caves all over Tessera!"
    end
  else
    say "QUARTZ: Come back anytime. Crystals are patient, and so am I."
  end
end

script cc_quartz_aide
  faceplayer
  if flag hall_of_fame
    say "AIDE: The Champion! The sealed crystal to the south has started to glow. Quartz thinks something lives down there."
  else
    say "AIDE: These crystals hum when a Pokémon Terastallizes nearby. Isn't that amazing?"
  end
end

script cc_camp_rest
  faceplayer
  say "Camping in a cave is tiring. Let your Pokémon rest by the fire."
  heal
  say "Your Pokémon look refreshed!"
end

# ------------------------------------------------------------------ Route 11 Sea Cottage: Lapras gift quest
script r11_lapras_lady
  faceplayer
  if flag p3_got_lapras
    say "Lapras is happy with you, I can tell. Sing to it on calm nights!"
  elif not flag p3_lapras_quest
    say "Oh, a traveler! I've been caring for a young Lapras that washed up in a fishing net.\pIt's healed, but it's homesick for the open sea. It wants to travel with someone strong."
    say "Lapras loves pearls. A big one is said to be buried on the little islet in the bay. If you found one, I'm sure it would trust you."
    setflag p3_lapras_quest
    quest p3_lapras start
  elif item bigpearl
    say "That's a Big Pearl! Lapras is singing!"
    takeitem bigpearl
    givemon lapras 40 moves=surf,icebeam,sing,bodyslam
    if result 1
      setflag p3_got_lapras
      quest p3_lapras done
      say "Please take good care of it. It can carry you across any sea!"
    end
  else
    say "Lapras is waiting for a Big Pearl. Try the tiny islet in the middle of the bay."
  end
end

script r11_cottage_fisher
  faceplayer
  if not flag p3_got_goodrod_tip
    say "I'm the one who found Lapras in my net. Felt terrible!\pHere, take this. Fish around Route 11 with it and you'll hook some real beauties."
    giveitem diveball 3
    setflag p3_got_goodrod_tip
  else
    say "Mantine glide over the waves out there. Some say a blue beast runs across the water at dusk..."
  end
end
