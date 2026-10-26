# Part 3C: ferry between Port Tidewater and the Coral Isles.
# `ferry_to_coral` is called by Part 1's `tidewater_ferry` (sailor on the north pier) only after `hall_of_fame`.

script ferry_to_coral
  if not flag p3c_first_ferry
    say "SAILOR: Well, if it isn't the new Champion! The S.S. Aquila sails for the Coral Isles. Only Champions may board."
  else
    say "SAILOR: Back for more island air, Champion?"
  end
  ask "Board the ferry to the Coral Isles?"
  if result yes
    say "SAILOR: All aboard! Anchors aweigh!"
    fadeout
    music ocean_surf
    say "The S.S. Aquila cut through the sparkling sea...\pWingull wheeled overhead. Far off, a Wailord spouted.\p...Land ho! The Coral Isles!"
    warp coral_town 11 22 up
    if not flag p3c_first_ferry
      setflag p3c_first_ferry
      say "Warm wind, white sand and coral reefs as far as the eye can see.\pThe Coral Isles: where Tessera's legends are said to sleep."
      quest p3c_isles start
    end
  else
    say "SAILOR: The sea will wait. Come back anytime!"
  end
end

script p3c_ferry_return
  faceplayer
  say "SAILOR: The S.S. Aquila runs back to Port Tidewater whenever you like."
  ask "Sail back to Port Tidewater?"
  if result yes
    say "SAILOR: Hoist the sails!"
    fadeout
    music ocean_surf
    say "The Coral Isles shrank on the horizon...\p...Port Tidewater's harbor came into view."
    warp port_tidewater 27 6 left
  else
    say "SAILOR: Enjoy the isles!"
  end
end
