# Part 3B: Umbral Pass and Prism Summit (Admins Vesper and Corvin, Boss Umbra, Necrozma awakens).
# Necrozma is a scripted wildbattle: catch it (p3b_necrozma_caught) or defeat it (p3b_necrozma_defeated, which
# Part 3C uses to make it reappear after the League). Either way p3b_necrozma_done and p3b_eclipse_defeated are set.

script p3b_umbral_scout
  faceplayer
  if flag p3b_eclipse_defeated
    say "SCOUT: The pass is quiet again. Even the Weavile seem relieved."
  else
    say "SCOUT: Clan scout, reporting! A whole column of Team Eclipse went down this pass toward the summit caves.\pThe cave mouth is at the far south end. I'll keep watch here. Be careful, {player}!"
  end
end

# --- Prism Summit --------------------------------------------------------------
script p3b_summit_scout
  faceplayer
  if flag p3b_eclipse_defeated
    say "SCOUT: I'm mapping the crystal halls for the Clan archives. Mostly I'm mapping where I tripped."
  else
    say "SCOUT: Psst! Clan scout, undercover. Well, under a rock. Let me tend to your Pokémon."
    heal
    say "SCOUT: Admin Vesper waits past the north room. Her Pokémon are Psychic and Ghost. Dark moves will serve you well."
  end
end

script p3b_ps2_lens_tech
  faceplayer
  if flag p3b_eclipse_defeated
    say "TECHNICIAN: I'm just here to dismantle the relay. I quit Team Eclipse. Mostly because there's no more Team Eclipse."
  else
    say "TECHNICIAN: Don't hurt me! I just maintain the relay crystals.\pThey pipe light up to Lord Umbra's lens at the summit. When it's full, he'll aim it at the Prism Pokémon. Something about 'feeding it until it never wakes'..."
  end
end

script p3b_vesper
  face vesper right
  say "VESPER: So, the light-bearer arrives exactly on schedule. I am Vesper, Admin of Team Eclipse.\pI have seen a thousand futures. In each one, the eclipse falls. Let me show you yours."
  battle p3b_admin_vesper
  if result win
    say "VESPER: The visions... flicker. Interesting.\pCorvin guards the last corridor. He has been sulking about you since Mt. Prism."
    setflag p3b_beat_vesper
    fadeout
    hide vesper
    fadein
  end
end

script p3b_summit_rival
  faceplayer
  if not flag p3b_summit_rival_met
    setflag p3b_summit_rival_met
    say "{rival}: {player}! Took you long enough! I followed the Clan scouts up here and I've been knocking out grunts all morning.\pHere, let me patch your team up. Teamwork, right?"
  else
    say "{rival}: Need a breather? Hold still."
  end
  heal
  say "{rival}: Corvin's holed up in the west corridor, past the north room. Umbra's at the very top. I'll keep this floor clear. Go!"
end

script p3b_corvin
  face corvin right
  say "CORVIN: You. Of course it's you. The brat who dimmed my siphon on Mt. Prism.\pLord Umbra stands above us, at the altar. You will not take another step!"
  battle p3b_admin_corvin
  if result win
    say "CORVIN: Again... How is your light still so bright?\pFine. Go up there. Let Lord Umbra show you what true darkness is."
    setflag p3b_beat_corvin
    quest p3b_story next
    fadeout
    hide corvin
    fadein
  end
end

script p3b_umbra
  face umbra down
  say "UMBRA: Stop there, child. Look around you. The Prism's light flows into my lens, and soon the sky above Tessera will never burn again.\pNo glare, no scorched fields, no one squinting at a cruel sun. Only a gentle, endless dusk."
  say "UMBRA: You have chased my people across the whole region. Admirable. But your light ends here!"
  battle p3b_boss_umbra
  if result win
    setflag p3b_beat_umbra
    say "UMBRA: Impossible... Hah. It hardly matters. The lens is full!\pWake, Prism Pokémon! Wake, and swallow the light forever!"
    shake
    cry necrozma
    flash
    say "The great lens cracks... The crystal altar erupts in a blinding flash!"
    fadeout
    hide umbra
    hide pk_grunt1
    hide pk_grunt2
    show necrozma
    fadein
    say "UMBRA: What...? It isn't obeying! It's devouring the lens itself... Retreat! Everyone, retreat!"
    call p3b_necrozma
  end
end

script p3b_necrozma
  say "A shape of black crystal unfolds above the altar, drinking in every scrap of light!"
  cry necrozma
  wildbattle necrozma 60 norun
  if result caught
    setflag p3b_necrozma_caught
    setflag p3b_necrozma_done
    hide necrozma
    say "The Prism Pokémon rests quietly in its Poké Ball. Its crystal body glows softly, full at last."
    call p3b_summit_epilogue
  elif result defeated
    setflag p3b_necrozma_defeated
    setflag p3b_necrozma_done
    hide necrozma
    say "Necrozma shattered into motes of light and streaked off into the sky.\pThe stolen light pours back into Mt. Prism..."
    call p3b_summit_epilogue
  end
end

script p3b_summit_epilogue
  say "The purple sky cracks open, and warm sunlight spills across the summit."
  fadeout
  say "{rival}: {player}! Are you okay?! The whole mountain lit up like a lighthouse!\pRYUGA: Team Eclipse has scattered down the mountain. Umbra fled with them, with nothing left but a broken lens."
  say "RYUGA: You have done the Clan, and all of Tessera, a great service. The League has already reopened Route 13. Come, let us get you down from here."
  setflag p3b_eclipse_defeated
  quest p3b_story next
  heal
  warp dragonspire_city 25 9 down
  fadein
  say "RYUGA: Rest well tonight, {player}. Victory Road lies east along Route 13, and beyond it, the League on Prism Plateau."
end
