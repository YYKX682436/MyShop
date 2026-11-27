import assert from "node:assert/strict"
import test from "node:test"
import { readFileSync } from "node:fs"

const readSource = (relativePath) => readFileSync(new URL(relativePath, import.meta.url), "utf8")

test("account home owns the complete logged-in summary and scoped account actions", () => {
  const source = readSource("../app/(store)/my/page.tsx")
  const avatarSource = readSource("../components/store/account-avatar.tsx")
  const uiAvatarSource = readSource("../components/ui/avatar.tsx")
  const styles = readSource("../app/globals.css")
  const dictionary = readSource("./i18n.ts")
  const overviewType = readSource("../types/index.ts")
  const loadingSource = source.slice(source.indexOf("function LoadingAccount"), source.indexOf("export default function MyPage"))
  const accountBalanceStart = source.indexOf('<section className="account-balance p-2 sm:p-3" aria-label=')
  const upgradeEntryStart = source.indexOf("{shouldShowUpgradeEntry &&", accountBalanceStart)
  const accountBalance = source.slice(accountBalanceStart, upgradeEntryStart)
  const accountBalanceContent = accountBalance.slice(accountBalance.indexOf(">") + 1)

  for (const route of ["/my/subsite", "/my/security", "/my/settings"]) {
    assert.equal(source.includes(`href="${route}"`), true, route)
  }
  assert.doesNotThrow(() => readSource("../app/(store)/my/orders/page.tsx"))
  assert.match(source, /user\.avatar_url/)
  assert.match(source, /user\.user_no/)
  assert.match(source, /user\.balance/)
  assert.match(source, /user\.user_level_name/)
  assert.match(source, /const copyAccountValue = async/)
  assert.match(source, /copyText\(value\)/)
  assert.match(source, /copyAccountValue\(user\.username, t\("my\.usernameCopied"\)\)/)
  assert.match(source, /aria-label=\{t\("my\.copyUsername"\)\}/)
  assert.match(source, /type="button"[\s\S]*aria-label=\{t\("my\.copyUsername"\)\}[\s\S]*focus-visible:ring-2/)
  const identityPillStart = source.indexOf('className="mt-1 inline-flex h-7 w-fit min-w-0 max-w-full items-center gap-1.5 rounded-full')
  const identityPillEnd = source.indexOf("\n              </div>", identityPillStart)
  const identityPill = source.slice(identityPillStart, identityPillEnd)
  assert.ok(identityPillStart >= 0)
  assert.match(identityPill, /user\.user_level_name/)
  assert.match(identityPill, /<Crown/)
  assert.match(identityPill, /user\.user_no !== null/)
  assert.match(identityPill, /handleCopyUserId/)
  assert.match(identityPill, /h-3 w-px/)
  assert.match(identityPill, /text-current/)
  assert.match(identityPill, /text-\[11px\]/)
  assert.doesNotMatch(identityPill, /py-1/)
  assert.match(source, /const identityBadge = isAdmin \?/)
  assert.match(source, /\{isAdmin \? identityBadge : hasUserIdentity/)
  assert.match(source, /user\.user_no !== null[\s\S]*handleCopyUserId/)
  assert.match(source, /copyAccountValue\(String\(user\.user_no\), t\("my\.userIdCopied"\)\)/)
  assert.match(source, /formatYuan\(user\.balance\)/)
  assert.match(source, /account-balance p-2 sm:p-3/)
  assert.match(source, /<Wallet className="size-\[18px\]"/)
  assert.match(source, /<span className="-translate-x-0\.5 flex size-9[\s\S]*sm:-translate-x-1\.5/)
  assert.match(source, /group flex min-h-12 items-center gap-3 px-4[\s\S]*-translate-x-1[\s\S]*sm:translate-x-0/)
  assert.match(source, /balanceIsHistoryLink = !user\.unlimited && user\.role === "USER"[\s\S]*!accountOverview\.recharge_enabled/)
  assert.match(accountBalance, /href="\/my\/balance#history"/)
  assert.match(accountBalance, /href="\/my\/balance"/)
  assert.match(accountBalance, /accountOverview\?\.recharge_enabled/)
  assert.doesNotMatch(accountBalance, /balanceDetails|<ChevronRight/)
  assert.match(accountBalance, /aria-label=\{t\("my\.balanceHistory"\)\}/)
  assert.doesNotMatch(accountBalanceContent, /formatYuan|\{t\("my\.balance"\)\}/)
  assert.match(source, /const balanceLabel = user\.unlimited \? "∞" : formatYuan\(user\.balance\)/)
  assert.doesNotMatch(source, /WalletCards/)
  assert.match(source, /userApi\.uploadAvatar/)
  assert.match(source, /setUser\(updatedProfile\)/)
  assert.doesNotMatch(source, /user\.email/)
  assert.doesNotMatch(source, /\bCopy\b/)
  assert.doesNotMatch(source, /<Copy/)
  assert.doesNotMatch(source, /AccountStat/)
  assert.doesNotMatch(source, /my\.userLevel|my\.role/)
  assert.doesNotMatch(source, /user\.id/)
  assert.doesNotMatch(source, /user\.user_no \?\?|user\.user_no \|\|/)
  assert.match(source, /shouldShowUpgradeEntry = user\.role === "USER" && !user\.unlimited[\s\S]*accountOverview\.has_higher_level \|\| accountOverview\.is_highest_level/)
  assert.match(source, /href="\/my\/levels" icon=\{Crown\} label=\{t\("my\.levelUpgrade"\)\}/)
  assert.doesNotMatch(source, /accountOverview\?\.upgrade_available/)
  assert.equal((dictionary.match(/"my\.copyUsername"/g) || []).length, 2)
  assert.equal((dictionary.match(/"my\.usernameCopied"/g) || []).length, 2)
  assert.equal((dictionary.match(/"my\.highestLevelTitle"/g) || []).length, 2)
  assert.match(loadingSource, /<header[\s\S]*aria-hidden="true"/)
  assert.equal((loadingSource.match(/<section/g) || []).length, 2)
  assert.match(loadingSource, /<section className="content-surface rounded-card p-5 sm:p-6"/)
  assert.match(loadingSource, /<section className="account-balance p-2 sm:p-3/)
  const identitySkeletonStart = loadingSource.indexOf('<section className="content-surface')
  const balanceSkeletonStart = loadingSource.indexOf('<section className="account-balance p-2 sm:p-3"')
  assert.ok(balanceSkeletonStart > identitySkeletonStart)
  assert.match(loadingSource.slice(identitySkeletonStart, balanceSkeletonStart), /h-7 w-40 max-w-full/)
  assert.doesNotMatch(loadingSource.slice(identitySkeletonStart, balanceSkeletonStart), /justify-between/)
  assert.doesNotMatch(loadingSource.slice(identitySkeletonStart, balanceSkeletonStart), /h-28/)
  assert.match(loadingSource.slice(identitySkeletonStart), /<\/section>\s*<section/)
  assert.match(loadingSource, /flex min-h-12 items-center justify-between/)
  assert.match(loadingSource, /-translate-x-0\.5 size-9[\s\S]*sm:-translate-x-1\.5/)
  const balanceCssStart = styles.indexOf("  .account-balance {")
  const balanceCssEnd = styles.indexOf("\n  }", balanceCssStart) + "\n  }".length
  assert.match(styles.slice(balanceCssStart, balanceCssEnd), /border-radius: var\(--radius-group\)/)
  assert.match(styles.slice(balanceCssStart, balanceCssEnd), /background-color: hsl\(var\(--primary\) \/ 0\.045\)/)
  assert.match(styles, /\.account-balance \{ border-color: hsl\(var\(--primary\) \/ 0\.14\); border-radius: var\(--radius-group\); \}/)
  assert.doesNotMatch(loadingSource, /setTimeout/)
  assert.doesNotMatch(source, /\/profile/)
  assert.doesNotMatch(source, /my\.darkMode|my\.english/)
  assert.match(overviewType, /is_highest_level: boolean/)
  assert.match(avatarSource, /<button/)
  assert.match(avatarSource, /<Avatar className="size-full overflow-hidden !rounded-card/)
  assert.match(avatarSource, /<AvatarFallback className="!rounded-card/)
  assert.match(uiAvatarSource, /cn\([\s\S]*rounded-full[\s\S]*className/)
  assert.match(avatarSource, /onLoadingStatusChange/)
  assert.match(avatarSource, /getAvatarDisplayState/)
  for (const removedToken of [
    ["Camera"],
    ["Rotate", "Ccw"],
    ["<", "Button"],
    ["on", "Delete"],
    ["change", "Avatar"],
    ["restore", "Avatar"],
  ]) {
    assert.doesNotMatch(avatarSource, new RegExp(removedToken.join("")))
  }
  for (const removedToken of [
    ["handleAvatar", "Delete"],
    ["delete", "Avatar"],
    ["avatar", "Restored"],
  ]) {
    assert.doesNotMatch(source, new RegExp(removedToken.join("")))
  }
})

test("highest-level page keeps upgrade transactions visible and skips purchase tools", () => {
  const source = readSource("../app/(store)/my/levels/page.tsx")
  const highestBranchStart = source.indexOf("{showHighestLevelState ? (")
  const normalBranchStart = source.indexOf(") : (", highestBranchStart)
  const highestBranch = source.slice(highestBranchStart, normalBranchStart)

  assert.match(source, /overview\?\.is_highest_level === true/)
  assert.match(source, /overview\?\.current_level_name/)
  assert.match(source, /t\("my\.highestLevelTitle"\)/)
  assert.match(source, /src="\/images\/level-upgrade-banner\.webp"[\s\S]*preload[\s\S]*sizes=/)
  assert.match(source, /alt=\{t\("my\.levelUpgradeBannerAlt"\)\}/)
  const dictionary = readSource("./i18n.ts")
  assert.equal((dictionary.match(/"my\.levelUpgradeBannerAlt"/g) || []).length, 2)
  assert.match(dictionary, /"my\.levelUpgradeBannerAlt": "升级代理等级，拿货价更低，利润更高；多等级价格对比，经营更有竞争力。"/)
  assert.match(source, /paymentApi\.getChannels\(\)/)
  assert.doesNotMatch(source, /Promise\.all\(\[userAccountApi\.getOverview\(\), paymentApi\.getChannels\(\)\]\)/)
  assert.match(source, /getTransaction\(transactionId\)/)
  assert.match(source, /refreshTransaction\(transactionId\)/)
  assert.match(source, /transaction\?\.type === "LEVEL_UPGRADE"/)
  assert.match(source, /transaction\?\.status === "COMPLETED"/)
  assert.match(source, /transaction\?\.status === "RECOVERED"/)
  assert.match(source, /transaction\?\.status === "PENDING"/)
  assert.match(source, /await refreshProfile\(\)[\s\S]*await loadOverview\(\)/)
  assert.ok(highestBranchStart >= 0)
  assert.match(highestBranch, /<Crown/)
  assert.match(highestBranch, /my\.highestLevelTitle/)
  assert.match(highestBranch, /current_level_name/)
  assert.doesNotMatch(highestBranch, /target-level|upgradeFee|paymentMethod|PaymentSelector|productPriceSearch|price-search/)
  assert.match(source.slice(normalBranchStart), /target-level/)
  assert.match(source.slice(normalBranchStart), /productPriceSearch/)
})

test("security page presents the current email before the password form", () => {
  const source = readSource("../app/(store)/my/security/page.tsx")
  const dictionary = readSource("./i18n.ts")

  assert.match(source, /user\.email/)
  assert.match(source, /security\.email/)
  assert.match(source, /security\.emailNotBound/)
  assert.match(source, /Mail/)
  assert.ok(source.indexOf("security.email") < source.indexOf("<ChangePasswordForm"))
  assert.equal((dictionary.match(/"security\.email"/g) || []).length, 2)
  assert.equal((dictionary.match(/"security\.emailNotBound"/g) || []).length, 2)
})

test("security flow clears in-memory auth state and navigates to login after password change", () => {
  const source = readSource("../components/store/change-password-form.tsx")

  assert.match(source, /userApi\.updatePassword/)
  assert.match(source, /setUser\(null\)/)
  assert.match(source, /router\.replace\("\/login"\)/)
  assert.match(source, /security\.passwordChanged/)
})

test("settings flow delegates language and all three theme modes to existing contexts", () => {
  const source = readSource("../app/(store)/my/settings/page.tsx")

  assert.match(source, /useLocale/)
  assert.match(source, /setLocale/)
  assert.match(source, /useTheme/)
  assert.match(source, /setTheme/)
  assert.match(source, /value: "system"/)
  assert.match(source, /settings\.chinese/)
  assert.match(source, /settings\.english/)
})

test("the removed profile page has no storefront route or page implementation", () => {
  assert.throws(() => readFileSync(new URL("../app/(store)/profile/page.tsx", import.meta.url)), /ENOENT/)
})

test("avatar API surface keeps upload and removes default-avatar deletion", () => {
  const source = readSource("../services/api.ts")

  assert.match(source, /uploadAvatar/)
  assert.doesNotMatch(source, new RegExp(["delete", "Avatar"].join("")))
})
