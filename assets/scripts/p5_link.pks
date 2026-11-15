# Link play: the attendant at the side of every Pokémon Center counter opens the Link lobby (same as the start menu's LINK entry,
# which stays hidden until the player has the Pokédex).
script p5_link_attendant
  faceplayer
  if flag has_pokedex
    say "Welcome to the LINK CLUB!\pHere you can battle and trade with friends over a network."
    special link
    say "Please come again!"
  else
    say "Welcome to the LINK CLUB!\pLink play opens once you have a Pokédex."
  end
end
